/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.pulsar.functions.transforms.predicate.jstl;

import com.datastax.oss.pulsar.functions.transforms.TransformContext;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.collections4.Transformer;
import org.apache.commons.collections4.map.LazyMap;
import org.apache.pulsar.functions.api.Record;

/**
 * A java bean that adapts the underlying {@link TransformContext} to be ready for jstl expression
 * language binding.
 */
public class JstlTransformContextAdapter {
  private TransformContext transformContext;

  /**
   * A key transformer backing the lazy key map. It transforms top level key fields to either a
   * value or another lazy map for nested evaluation.
   */
  private Transformer<String, Object> keyTransformer =
      (fieldName) -> {
        if (this.transformContext.getKeyObject() instanceof GenericRecord) {
          GenericRecord genericRecord = (GenericRecord) this.transformContext.getKeyObject();
          JstlPredicate.GenericRecordTransformer transformer =
              new JstlPredicate.GenericRecordTransformer(genericRecord);
          return transformer.transform(fieldName);
        }
        return null;
      };

  private final Map<String, Object> lazyKey = LazyMap.lazyMap(new HashMap<>(), keyTransformer);

  /**
   * A value transformer backing the lazy value map. It transforms top level value fields to either
   * a value or another lazy map for nested evaluation.
   */
  private Transformer<String, Object> valueTransformer =
      (fieldName) -> {
        if (this.transformContext.getValueObject() instanceof GenericRecord) {
          GenericRecord genericRecord = (GenericRecord) this.transformContext.getValueObject();
          JstlPredicate.GenericRecordTransformer transformer =
              new JstlPredicate.GenericRecordTransformer(genericRecord);
          return transformer.transform(fieldName);
        }
        return null;
      };

  private final Map<String, Object> lazyValue = LazyMap.lazyMap(new HashMap<>(), valueTransformer);

  /** A header transformer to return message headers the user is allowed to filter on. */
  private Transformer<String, Object> headerTransformer =
      (fieldName) -> {
        Record<?> currentRecord = transformContext.getContext().getCurrentRecord();
        return currentRecord
            .getMessage()
            .map(
                message -> {
                  // Allow list message headers in the expression
                  switch (fieldName) {
                    case "key":
                      return message.getKey();
                    case "topicName":
                      return message.getTopicName();
                    case "properties":
                      return message.getProperties();
                    case "producerName":
                      return message.getProducerName();
                    default:
                      return null;
                  }
                })
            .orElse(null);
      };

  private final Map<String, Object> lazyHeader =
      LazyMap.lazyMap(new HashMap<>(), headerTransformer);

  public JstlTransformContextAdapter(TransformContext transformContext) {
    this.transformContext = transformContext;
  }

  public Map<String, Object> getKey() {
    return lazyKey;
  }

  public Map<String, Object> getValue() {
    return lazyValue;
  }

  public Map<String, Object> getHeader() {
    return lazyHeader;
  }
}
