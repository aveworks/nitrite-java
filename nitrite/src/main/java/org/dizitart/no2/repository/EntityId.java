/*
 * Copyright (c) 2017-2022 Nitrite author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.dizitart.no2.repository;

import lombok.Getter;
import org.dizitart.no2.NitriteConfig;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.NitriteMapper;
import org.dizitart.no2.exceptions.ObjectMappingException;
import org.dizitart.no2.filters.Filter;
import org.dizitart.no2.filters.NitriteFilter;

import java.util.ArrayList;
import java.util.List;

import static org.dizitart.no2.filters.FluentFilter.where;

public class EntityId {
    @Getter
    private String fieldName;

    @Getter
    private String[] subFields;

    private List<String> embeddedFieldNames;

    public EntityId(String fieldName, String... subFields) {
        this.fieldName = fieldName;
        this.subFields = subFields;
    }

    public List<String> getEmbeddedFieldNames() {
        if (embeddedFieldNames != null) return embeddedFieldNames;
        embeddedFieldNames = new ArrayList<>();

        if (subFields != null) {
            for (String subField : subFields) {
                embeddedFieldNames.add(fieldName + NitriteConfig.getFieldSeparator() + subField);
            }
        }
        return embeddedFieldNames;
    }

    public boolean isEmbedded() {
        return subFields != null && subFields.length != 0;
    }

    public Filter createUniqueFilter(Object value, NitriteMapper nitriteMapper) {
        if (isEmbedded()) {
            Document document = nitriteMapper.convert(value, Document.class);
            if (document == null) {
                throw new ObjectMappingException("Failed to map object to document");
            }

            List<Filter> filters = new ArrayList<>();
            for (String subField : subFields) {
                String filterField = fieldName + NitriteConfig.getFieldSeparator() + subField;
                Object fieldValue = document.get(subField);
                filters.add(where(filterField).eq(fieldValue));
            }

            NitriteFilter nitriteFilter = (NitriteFilter) Filter.and(filters.toArray(new Filter[] {}));
            nitriteFilter.setObjectFilter(true);
            return nitriteFilter;
        } else {
            return where(fieldName).eq(value);
        }
    }
}
