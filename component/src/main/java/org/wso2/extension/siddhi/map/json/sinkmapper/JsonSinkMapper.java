/*
 * Copyright (c)  2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.extension.siddhi.map.json.sinkmapper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.apache.log4j.Logger;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.stream.output.sink.SinkListener;
import org.wso2.siddhi.core.stream.output.sink.SinkMapper;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.OptionHolder;
import org.wso2.siddhi.core.util.transport.TemplateBuilder;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

import java.util.Map;


/**
 * Mapper class to convert a Siddhi message to a JSON message. User can provide a JSON template or else we will be
 * using a predefined JSON message format. In some instances
 * coding best practices have been compensated for performance concerns.
 */

@Extension(
        name = "json",
        namespace = "sinkMapper",
        description = "" +
                "Event to JSON output mapper. \n" +
                "Transports which publish  messages can utilize this extension" +
                "to convert the Siddhi event to " +
                "JSON message. \n" +
                "Users can either send a pre-defined JSON format or a custom JSON message.\n",
        parameters = {
                @Parameter(name = "validate.json",
                        description = "" +
                                "This property will enable JSON validation for generated JSON message. \n" +
                                "By default value of the property will be false. \n" +
                                "When enabled, DAS will validate the generated JSON message and drop the message " +
                                "if it does not adhere to proper JSON standards.\n",
                        type = {DataType.BOOL}),
                @Parameter(name = "enclosing.element",
                        description =
                                "Used to specify the enclosing element in case of sending multiple events in same "
                                        + "JSON message. \nWSO2 DAS will treat the child element of given enclosing "
                                        + "element as events"
                                        + " and execute json expressions on child elements. \nIf enclosing.element "
                                        + "is not provided "
                                        + "multiple event scenario is disregarded and json path will be evaluated "
                                        + "with respect to root element.",
                        type = {DataType.STRING})
        },
        examples = {
                @Example(
                        syntax = "@sink(type='inMemory', topic='stock', @map(type='json'))\n"
                                + "define stream FooStream (symbol string, price float, volume long);\n",
                        description = "Above configuration will do a default JSON input mapping which will "
                                + "generate below output.\n"
                                + "{\n"
                                + "    \"event\":{\n"
                                + "        \"symbol\":WSO2,\n"
                                + "        \"price\":55.6,\n"
                                + "        \"volume\":100\n"
                                + "    }\n"
                                + "}\n"),
                @Example(
                        syntax = "@sink(type='inMemory', topic='{{symbol}}', @map(type='json', enclosing"
                                + ".element='$.portfolio', validate.json='true', @payload( "
                                + "\"{\"StockData\":{\"Symbol\":\"{{symbol}}\",\"Price\":{{price}}}\")))\n"
                                + "define stream BarStream (symbol string, price float, volume long);",
                        description = "Above configuration will perform a custom JSON mapping which will "
                                + "produce below output JSON message\n"
                                + "{"
                                + "\"portfolio\":{\n"
                                + "    \"StockData\":{\n"
                                + "        \"Symbol\":WSO2,\n"
                                + "        \"Price\":55.6\n"
                                + "      }\n"
                                + "  }\n"
                                + "}")
        }
)

public class JsonSinkMapper extends SinkMapper {
    private static final Logger log = Logger.getLogger(JsonSinkMapper.class);
    private static final String EVENT_PARENT_TAG = "event";
    private static final String ENCLOSING_ELEMENT_IDENTIFIER = "enclosing.element";
    private static final String DEFAULT_ENCLOSING_ELEMENT = "$";
    private static final String JSON_VALIDATION_IDENTIFIER = "validate.json";
    private static final String JSON_EVENT_SEPERATOR = ",";
    private static final String JSON_KEYVALUE_SEPERATOR = ":";
    private static final String JSON_ARRAY_START_SYMBOL = "[";
    private static final String JSON_ARRAY_END_SYMBOL = "]";
    private static final String JSON_EVENT_START_SYMBOL = "{";
    private static final String JSON_EVENT_END_SYMBOL = "}";
    private static final String UNDEFINED = "undefined";
    private static final String PUBLISH_AS_CHUNKS = "publish.as.chunks";

    private String[] attributeNameArray;
    private String enclosingElement = null;
    private boolean isJsonValidationEnabled = false;
    private boolean publishAsChunks = true;


    @Override
    public String[] getSupportedDynamicOptions() {
        return new String[0];
    }


    @Override
    public Class[] getOutputEventClasses() {
        return new Class[]{String.class};
    }

    /**
     * Initialize the mapper and the mapping configurations.
     *
     * @param streamDefinition          The stream definition
     * @param optionHolder              Option holder containing static and dynamic options
     * @param payloadTemplateBuilderMap Unmapped list of payloads for reference
     */
    @Override
    public void init(StreamDefinition streamDefinition, OptionHolder optionHolder,
                     Map<String, TemplateBuilder> payloadTemplateBuilderMap, ConfigReader mapperConfigReader,
                     SiddhiAppContext siddhiAppContext) {

        attributeNameArray = streamDefinition.getAttributeNameArray();
        enclosingElement = optionHolder.validateAndGetStaticValue(ENCLOSING_ELEMENT_IDENTIFIER, null);
        isJsonValidationEnabled = Boolean.parseBoolean(optionHolder
                .validateAndGetStaticValue(JSON_VALIDATION_IDENTIFIER, "false"));
        publishAsChunks = Boolean.parseBoolean(optionHolder.validateAndGetStaticValue(PUBLISH_AS_CHUNKS, "true"));



        //if @payload() is added there must be at least 1 element in it, otherwise a SiddhiParserException raised
        if (payloadTemplateBuilderMap != null && payloadTemplateBuilderMap.size() != 1) {
            throw new SiddhiAppCreationException("Json sink-mapper does not support multiple @payload mappings, " +
                    "error at the mapper of '" + streamDefinition.getId() + "'");
        }
        if (payloadTemplateBuilderMap != null &&
                payloadTemplateBuilderMap.get(payloadTemplateBuilderMap.keySet().iterator().next()).isObjectMessage()) {
            throw new SiddhiAppCreationException("Json sink-mapper does not support object @payload mappings, " +
                    "error at the mapper of '" + streamDefinition.getId() + "'");
        }
    }

    @Override
    public void mapAndSend(Event[] events, OptionHolder optionHolder,
                           Map<String, TemplateBuilder> payloadTemplateBuilderMap, SinkListener sinkListener) {

        StringBuilder sb = new StringBuilder();
        if (payloadTemplateBuilderMap == null) {
            String jsonString = constructJsonForDefaultMapping(events);
            sb.append(jsonString);
        } else {
            sb.append(constructJsonForCustomMapping(events,
                    payloadTemplateBuilderMap.get(payloadTemplateBuilderMap.keySet().iterator().next())));
        }

        if (publishAsChunks) {
            if (!isJsonValidationEnabled) {
                sinkListener.publish(sb.toString());
            } else if (isValidJson(sb.toString())) {
                sinkListener.publish(sb.toString());
            } else {
                log.error("Invalid json string : " + sb.toString() + ". Hence dropping the message.");
            }
        } else {
            for (Event event : events) {
                this.mapAndSend(event, optionHolder, payloadTemplateBuilderMap, sinkListener);
            }
        }
    }

    @Override
    public void mapAndSend(Event event, OptionHolder optionHolder,
                           Map<String, TemplateBuilder> payloadTemplateBuilderMap, SinkListener sinkListener) {

        StringBuilder sb = null;
        if (payloadTemplateBuilderMap == null) {
            String jsonString = constructJsonForDefaultMapping(event);
            if (jsonString != null) {
                sb = new StringBuilder();
                sb.append(jsonString);
            }
        } else {
            sb = new StringBuilder();
            sb.append(constructJsonForCustomMapping(event,
                    payloadTemplateBuilderMap.get(payloadTemplateBuilderMap.keySet().iterator().next())));
        }

        if (sb != null) {
            if (!isJsonValidationEnabled) {
                sinkListener.publish(sb.toString());
            } else if (isValidJson(sb.toString())) {
                sinkListener.publish(sb.toString());
            } else {
                log.error("Invalid json string : " + sb.toString() + ". Hence dropping the message.");
            }
        }
    }

    private String constructJsonForDefaultMapping(Object eventObj) {
        StringBuilder sb = new StringBuilder();
        int numberOfOuterObjects;
        if (enclosingElement != null) {
            String[] nodeNames = enclosingElement.split("\\.");
            if (DEFAULT_ENCLOSING_ELEMENT.equals(nodeNames[0])) {
                numberOfOuterObjects = nodeNames.length - 1;
            } else {
                numberOfOuterObjects = nodeNames.length;
            }
            for (String nodeName : nodeNames) {
                if (!DEFAULT_ENCLOSING_ELEMENT.equals(nodeName)) {
                    sb.append(JSON_EVENT_START_SYMBOL).append("\"").append(nodeName).append("\"")
                            .append(JSON_KEYVALUE_SEPERATOR);
                }
            }
            if (eventObj instanceof Event) {
                Event event = (Event) eventObj;
                JsonObject jsonEvent = constructSingleEventForDefaultMapping(doPartialProcessing(event));
                sb.append(jsonEvent);
            } else if (eventObj instanceof Event[]) {
                JsonArray eventArray = new JsonArray();
                for (Event event : (Event[]) eventObj) {
                    eventArray.add(constructSingleEventForDefaultMapping(doPartialProcessing(event)));
                }
                sb.append(eventArray.toString());
            } else {
                log.error("Invalid object type. " + eventObj.toString() +
                        " cannot be converted to an event or event array. Hence dropping message.");
                return null;
            }
            for (int i = 0; i < numberOfOuterObjects; i++) {
                sb.append(JSON_EVENT_END_SYMBOL);
            }
            return sb.toString();
        } else {
            if (eventObj instanceof Event) {
                Event event = (Event) eventObj;
                JsonObject jsonEvent = constructSingleEventForDefaultMapping(doPartialProcessing(event));
                return jsonEvent.toString();
            } else if (eventObj instanceof Event[]) {
                JsonArray eventArray = new JsonArray();
                for (Event event : (Event[]) eventObj) {
                    eventArray.add(constructSingleEventForDefaultMapping(doPartialProcessing(event)));
                }
                return (eventArray.toString());
            } else {
                log.error("Invalid object type. " + eventObj.toString() +
                        " cannot be converted to an event or event array.");
                return null;
            }
        }
    }

    private String constructJsonForCustomMapping(Object eventObj, TemplateBuilder payloadTemplateBuilder) {
        StringBuilder sb = new StringBuilder();
        int numberOfOuterObjects = 0;
        if (enclosingElement != null) {
            String[] nodeNames = enclosingElement.split("\\.");
            if (DEFAULT_ENCLOSING_ELEMENT.equals(nodeNames[0])) {
                numberOfOuterObjects = nodeNames.length - 1;
            } else {
                numberOfOuterObjects = nodeNames.length;
            }
            for (String nodeName : nodeNames) {
                if (!DEFAULT_ENCLOSING_ELEMENT.equals(nodeName)) {
                    sb.append(JSON_EVENT_START_SYMBOL).append("\"").append(nodeName).append("\"")
                            .append(JSON_KEYVALUE_SEPERATOR);
                }
            }
            if (eventObj instanceof Event) {
                Event event = doPartialProcessing((Event) eventObj);
                sb.append(payloadTemplateBuilder.build(event));
            } else if (eventObj instanceof Event[]) {
                String jsonEvent;
                sb.append(JSON_ARRAY_START_SYMBOL);
                for (Event e : (Event[]) eventObj) {
                    jsonEvent = (String) payloadTemplateBuilder.build(doPartialProcessing(e));
                    if (jsonEvent != null) {
                        sb.append(jsonEvent).append(JSON_EVENT_SEPERATOR).append("\n");
                    }
                }
                sb.delete(sb.length() - 2, sb.length());
                sb.append(JSON_ARRAY_END_SYMBOL);
            } else {
                log.error("Invalid object type. " + eventObj.toString() +
                        " cannot be converted to an event or event array. Hence dropping message.");
                return null;
            }
            for (int i = 0; i < numberOfOuterObjects; i++) {
                sb.append(JSON_EVENT_END_SYMBOL);
            }
            return sb.toString();
        } else {
            if (eventObj.getClass() == Event.class) {
                return (String) payloadTemplateBuilder.build(doPartialProcessing((Event) eventObj));
            } else if (eventObj.getClass() == Event[].class) {
                String jsonEvent;
                sb.append(JSON_ARRAY_START_SYMBOL);
                for (Event event : (Event[]) eventObj) {
                    jsonEvent = (String) payloadTemplateBuilder.build(doPartialProcessing(event));
                    if (jsonEvent != null) {
                        sb.append(jsonEvent).append(JSON_EVENT_SEPERATOR).append("\n");
                    }
                }
                sb.delete(sb.length() - 2, sb.length());
                sb.append(JSON_ARRAY_END_SYMBOL);
                return sb.toString();
            } else {
                log.error("Invalid object type. " + eventObj.toString() +
                        " cannot be converted to an event or event array. Hence dropping message.");
                return null;
            }
        }
    }

    private JsonObject constructSingleEventForDefaultMapping(Event event) {
        Object[] data = event.getData();
        JsonObject jsonEventObject = new JsonObject();
        JsonObject innerParentObject = new JsonObject();
        String attributeName;
        Object attributeValue;
        Gson gson = new Gson();
        for (int i = 0; i < data.length; i++) {
            attributeName = attributeNameArray[i];
            attributeValue = data[i];
            if (attributeValue != null) {
                if (attributeValue.getClass() == String.class) {
                    innerParentObject.addProperty(attributeName, attributeValue.toString());
                } else if (attributeValue instanceof Number) {
                    innerParentObject.addProperty(attributeName, (Number) attributeValue);
                } else if (attributeValue instanceof Boolean) {
                    innerParentObject.addProperty(attributeName, (Boolean) attributeValue);
                } else if (attributeValue instanceof Map) {
                    if (!((Map) attributeValue).isEmpty()) {
                        innerParentObject.add(attributeName, gson.toJsonTree(attributeValue));
                    }
                }
            }
        }
        jsonEventObject.add(EVENT_PARENT_TAG, innerParentObject);
        return jsonEventObject;
    }

    private Event doPartialProcessing(Event event) {
        Object[] data = event.getData();
        for (int i = 0; i < data.length; i++) {
            if (data[i] == null) {
                data[i] = UNDEFINED;
            }
        }
        return event;
    }

    private static boolean isValidJson(String jsonInString) {
        try {
            new Gson().fromJson(jsonInString, Object.class);
            return true;
        } catch (com.google.gson.JsonSyntaxException ex) {
            return false;
        }
    }
}
