/*
 * Copyright (c) 2017-2020. Nitrite author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dizitart.no2.common.mapper;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.*;
import lombok.extern.slf4j.Slf4j;
import org.dizitart.no2.NitriteConfig;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.common.mapper.modules.NitriteIdModule;
import org.dizitart.no2.exceptions.ObjectMappingException;

import java.io.IOException;
import java.util.*;

import static org.dizitart.no2.common.util.ValidationUtils.notNull;

/**
 * @author Anindya Chatterjee
 */
@Slf4j
public class JacksonMapper implements NitriteMapper {
    private ObjectMapper objectMapper;

    protected ObjectMapper getObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.setVisibility(
                objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
            objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
            objectMapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
            objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            objectMapper.registerModule(new NitriteIdModule());
        }
        return objectMapper;
    }

    public void registerJacksonModule(Module module) {
        notNull(module, "module cannot be null");
        getObjectMapper().registerModule(module);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <Source, Target> Target convert(Source source, Class<Target> type) {
        if (source == null) {
            return null;
        }

        try {
            JsonNode node = getObjectMapper().convertValue(source, JsonNode.class);
            if (node == null) return null;

            if (node.isValueNode()) {
                return getNodeValue(node);
            } else {
                if (Document.class.isAssignableFrom(type)) {
                    return (Target) convertToDocument(source);
                } else if (source instanceof Document) {
                    return convertFromDocument((Document) source, type);
                }
            }
        } catch (Exception e) {
            throw new ObjectMappingException("Failed to convert object of type "
                + source.getClass() + " to type " + type, e);
        }

        throw new ObjectMappingException("Can't convert object to type " + type
            + ", try registering a jackson Module for it.");
    }


    @Override
    public void initialize(NitriteConfig nitriteConfig) {
    }

    protected <Target> Target convertFromDocument(Document source, Class<Target> type) {
        try {
            return getObjectMapper().convertValue(source, type);
        } catch (IllegalArgumentException iae) {
            if (iae.getCause() instanceof JsonMappingException) {
                JsonMappingException jme = (JsonMappingException) iae.getCause();
                if (jme.getMessage().contains("Cannot construct instance")) {
                    throw new ObjectMappingException(jme.getMessage());
                }
            }
            throw iae;
        }
    }

    protected <Source> Document convertToDocument(Source source) {
        JsonNode node = getObjectMapper().convertValue(source, JsonNode.class);
        return readDocument(node);
    }

    @SuppressWarnings("unchecked")
    private <T> T getNodeValue(JsonNode node) {
        switch (node.getNodeType()) {
            case NUMBER:
                return (T) node.numberValue();
            case STRING:
                return (T) node.textValue();
            case BOOLEAN:
                return (T) Boolean.valueOf(node.booleanValue());
            default:
                return null;
        }
    }

    private Document readDocument(JsonNode node) {
        Map<String, Object> objectMap = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            Object object = readObject(value);
            objectMap.put(name, object);
        }

        return Document.createDocument(objectMap);
    }

    private Object readObject(JsonNode node) {
        if (node == null)
            return null;
        try {
            switch (node.getNodeType()) {
                case ARRAY:
                    return readArray(node);
                case BINARY:
                    return node.binaryValue();
                case BOOLEAN:
                    return node.booleanValue();
                case MISSING:
                case NULL:
                    return null;
                case NUMBER:
                    return node.numberValue();
                case OBJECT:
                case POJO:
                    return readDocument(node);
                case STRING:
                    return node.textValue();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List readArray(JsonNode array) {
        if (array.isArray()) {
            List list = new ArrayList();
            Iterator iterator = array.elements();
            while (iterator.hasNext()) {
                Object element = iterator.next();
                if (element instanceof JsonNode) {
                    list.add(readObject((JsonNode) element));
                } else {
                    list.add(element);
                }
            }
            return list;
        }
        return null;
    }
}
