/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.aggregation.datasketches.theta;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import org.apache.datasketches.common.Family;
import org.apache.datasketches.common.Util;
import org.apache.datasketches.theta.SetOperation;
import org.apache.datasketches.theta.Union;
import org.apache.datasketches.thetacommon.ThetaUtil;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.aggregation.AggregateCombiner;
import org.apache.druid.query.aggregation.Aggregator;
import org.apache.druid.query.aggregation.AggregatorAndSize;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.BufferAggregator;
import org.apache.druid.query.aggregation.ObjectAggregateCombiner;
import org.apache.druid.query.aggregation.VectorAggregator;
import org.apache.druid.segment.BaseObjectColumnValueSelector;
import org.apache.druid.segment.ColumnInspector;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.vector.VectorColumnSelectorFactory;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public abstract class SketchAggregatorFactory extends AggregatorFactory
{
  public static final int DEFAULT_MAX_SKETCH_SIZE = 16384;

  // Smallest number of entries in an Aggregator. Each entry is a long. Based on the constructor of
  // HeapQuickSelectSketch and used by guessAggregatorHeapFootprint.
  private static final int MIN_ENTRIES_PER_AGGREGATOR = 1 << ThetaUtil.MIN_LG_ARR_LONGS;

  // Largest preamble size for the sketch stored in an Aggregator, in bytes. Based on Util.getMaxUnionBytes.
  private static final int LONGEST_POSSIBLE_PREAMBLE_BYTES = Family.UNION.getMaxPreLongs() << 3;

  protected final String name;
  protected final String fieldName;
  protected final int size;
  private final byte cacheId;

  public SketchAggregatorFactory(String name, String fieldName, Integer size, byte cacheId)
  {
    this.name = Preconditions.checkNotNull(name, "Must have a valid, non-null aggregator name");
    this.fieldName = Preconditions.checkNotNull(fieldName, "Must have a valid, non-null fieldName");

    this.size = size == null ? DEFAULT_MAX_SKETCH_SIZE : size;
    Util.checkIfIntPowerOf2(this.size, "size");

    this.cacheId = cacheId;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Aggregator factorize(ColumnSelectorFactory metricFactory)
  {
    BaseObjectColumnValueSelector selector = metricFactory.makeColumnValueSelector(fieldName);
    return new SketchAggregator(selector, size);
  }

  @Override
  public AggregatorAndSize factorizeWithSize(ColumnSelectorFactory metricFactory)
  {
    BaseObjectColumnValueSelector selector = metricFactory.makeColumnValueSelector(fieldName);
    final SketchAggregator aggregator = new SketchAggregator(selector, size);
    return new AggregatorAndSize(aggregator, aggregator.getInitialSizeBytes());
  }

  @SuppressWarnings("unchecked")
  @Override
  public BufferAggregator factorizeBuffered(ColumnSelectorFactory metricFactory)
  {
    BaseObjectColumnValueSelector selector = metricFactory.makeColumnValueSelector(fieldName);
    return new SketchBufferAggregator(selector, size, getMaxIntermediateSizeWithNulls());
  }

  @Override
  public VectorAggregator factorizeVector(VectorColumnSelectorFactory selectorFactory)
  {
    return new SketchVectorAggregator(selectorFactory, fieldName, size, getMaxIntermediateSizeWithNulls());
  }

  @Override
  public boolean canVectorize(ColumnInspector columnInspector)
  {
    return true;
  }

  @Override
  public Object deserialize(Object object)
  {
    return SketchHolder.deserialize(object);
  }

  @Override
  public Comparator<Object> getComparator()
  {
    return SketchHolder.COMPARATOR;
  }

  @Override
  public Object combine(Object lhs, Object rhs)
  {
    return SketchHolder.combine(lhs, rhs, size);
  }

  @Override
  public AggregateCombiner makeAggregateCombiner()
  {
    return new ObjectAggregateCombiner<SketchHolder>()
    {
      private final Union union = (Union) SetOperation.builder().setNominalEntries(size).build(Family.UNION);
      private final SketchHolder combined = SketchHolder.of(union);

      @Override
      public void reset(ColumnValueSelector selector)
      {
        union.reset();
        combined.invalidateCache();
        fold(selector);
      }

      @Override
      public void fold(ColumnValueSelector selector)
      {
        SketchHolder other = (SketchHolder) selector.getObject();
        if (other != null) {
          other.updateUnion(union);
          combined.invalidateCache();
        }
      }

      @Override
      public Class<SketchHolder> classOfObject()
      {
        return SketchHolder.class;
      }

      @Nullable
      @Override
      public SketchHolder getObject()
      {
        return combined;
      }
    };
  }

  @Override
  @JsonProperty
  public String getName()
  {
    return name;
  }

  @JsonProperty
  public String getFieldName()
  {
    return fieldName;
  }

  @JsonProperty
  public int getSize()
  {
    return size;
  }

  @Override
  public int guessAggregatorHeapFootprint(long rows)
  {
    final int maxEntries = size * 2;
    final int expectedEntries;

    if (rows > maxEntries) {
      expectedEntries = maxEntries;
    } else {
      // rows is within int range since it's <= maxEntries, so casting is OK.
      expectedEntries = Math.max(MIN_ENTRIES_PER_AGGREGATOR, Util.ceilingIntPowerOf2(Ints.checkedCast(rows)));
    }

    // 8 bytes per entry + largest possible preamble.
    return Long.BYTES * expectedEntries + LONGEST_POSSIBLE_PREAMBLE_BYTES;
  }

  @Override
  public int getMaxIntermediateSize()
  {
    return SetOperation.getMaxUnionBytes(size);
  }

  @Override
  public List<String> requiredFields()
  {
    return Collections.singletonList(fieldName);
  }

  @Override
  public byte[] getCacheKey()
  {
    byte[] fieldNameBytes = StringUtils.toUtf8(fieldName);
    return ByteBuffer.allocate(1 + Integer.BYTES + fieldNameBytes.length)
                     .put(cacheId)
                     .putInt(size)
                     .put(fieldNameBytes)
                     .array();
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "{"
           + "fieldName='" + fieldName + '\''
           + ", name='" + name + '\''
           + ", size=" + size
           + '}';
  }


  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    SketchAggregatorFactory that = (SketchAggregatorFactory) o;

    if (size != that.size) {
      return false;
    }
    if (cacheId != that.cacheId) {
      return false;
    }
    if (!name.equals(that.name)) {
      return false;
    }
    return fieldName.equals(that.fieldName);

  }

  @Override
  public int hashCode()
  {
    int result = name.hashCode();
    result = 31 * result + fieldName.hashCode();
    result = 31 * result + size;
    result = 31 * result + (int) cacheId;
    return result;
  }
}
