/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.datamapper.engine.input.readers;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.datamapper.engine.core.Schema;
import org.wso2.datamapper.engine.core.exceptions.InvalidPayloadException;
import org.wso2.datamapper.engine.core.exceptions.JSException;
import org.wso2.datamapper.engine.core.schemas.SchemaElement;
import org.wso2.datamapper.engine.input.InputModelBuilder;
import org.json.simple.parser.ContentHandler;
import org.wso2.datamapper.engine.input.readers.events.DMReaderEvent;
import org.wso2.datamapper.engine.types.ReaderEventTypes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import static org.wso2.datamapper.engine.utils.DataMapperEngineConstants.*;

public class JSONSimpleJSONReader implements org.wso2.datamapper.engine.input.Readable, ContentHandler {
    private static final Log log = LogFactory.getLog(JSONSimpleJSONReader.class);
    private InputModelBuilder modelBuilder;
    private Schema inputSchema;
    private Stack<DMReaderEvent> dmEventStack;
    private List<SchemaElement> elementStack;

    public Schema getInputSchema() {
        return inputSchema;
    }

    @Override
    public void read(InputStream input, InputModelBuilder inputModelBuilder, Schema inputSchema) {
        dmEventStack = new Stack<>();
        elementStack = new ArrayList();
        modelBuilder = inputModelBuilder;
        this.inputSchema = inputSchema;
        Reader reader = new InputStreamReader(input);
        JSONParser parser = new JSONParser();
        try {
            parser.parse(reader, this);
        } catch (IOException e) {
            log.error("IO Error while parsing JSON input stream.", e);
        } catch (ParseException e) {
            log.error("Error while parsing JSON input stream.", e);
        }
    }

    @Override
    public void startJSON() throws ParseException, IOException {
    }

    @Override
    public void endJSON() throws ParseException, IOException {
        try {
            sendTerminateEvent();
        } catch (IOException | JSException e) {
            log.error("Error occurred while sending termination event", e);
        }
    }

    @Override
    public boolean startObject() throws ParseException, IOException {
        try {
            if (!getDmEventStack().isEmpty()) {
                DMReaderEvent stackElement = getDmEventStack().peek();
                String type = getInputSchema().getElementTypeByName(elementStack);
                if (ReaderEventTypes.EventType.OBJECT_START.equals(stackElement.getEventType())) {
                    if (ARRAY_ELEMENT_TYPE.equals(type)) {
                        log.error("Schema specifies an array of type " + type +
                                ". But payload doesn't contain an array.");
                        return false;
                    }
                    sendObjectStartEvent(stackElement.getName());
                    return true;
                }
            } else {
                elementStack.add(new SchemaElement(getInputSchema().getName()));
            }
            sendAnonymousObjectStartEvent();
        } catch (JSException e) {
            log.error("Error occurred while processing start object event", e);
        } catch (InvalidPayloadException e) {
            log.error(e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean endObject() throws ParseException, IOException {
        if (!getDmEventStack().isEmpty()) {
            DMReaderEvent stackElement = getDmEventStack().peek();
            try {
                sendObjectEndEvent(stackElement.getName());
            } catch (JSException e) {
                log.error("Error occurred while processing end object event", e);
            } catch (InvalidPayloadException e) {
                log.error(e.getMessage(), e);
            }
        }
        return true;
    }

    @Override
    public boolean startObjectEntry(String s) throws ParseException, IOException {
        try {
            elementStack.add(new SchemaElement(s));
            String type = getInputSchema().getElementTypeByName(elementStack);
            if (ARRAY_ELEMENT_TYPE.equals(type)) {
                pushObjectStartEvent(s);
            } else if (OBJECT_ELEMENT_TYPE.equals(type)) {
                pushObjectStartEvent(s);
            } else if (STRING_ELEMENT_TYPE.equals(type) || BOOLEAN_ELEMENT_TYPE.equals(type)
                    || NUMBER_ELEMENT_TYPE.equals(type) || INTEGER_ELEMENT_TYPE.equals(type) ||
                    NULL_ELEMENT_TYPE.equals(type)) {
                pushObjectStartEvent(s);
            }
        } catch (IOException | JSException e) {
            log.error("Error occurred while processing start element event", e);
        } catch (InvalidPayloadException e) {
            log.error(e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean endObjectEntry() throws ParseException, IOException {
        if (!getDmEventStack().isEmpty()) {
            DMReaderEvent stackElement = getDmEventStack().peek();
            try {
                popObjectEndEvent(stackElement.getName());
                elementStack.remove(elementStack.size() - 1);
            } catch (JSException e) {
                log.error("Error while sending end object entry");
            } catch (InvalidPayloadException e) {
                log.error(e.getMessage(), e);
            }
        }
        return true;
    }

    @Override
    public boolean startArray() throws ParseException, IOException {
        try {
            if (!getDmEventStack().isEmpty()) {
                DMReaderEvent stackElement = getDmEventStack().peek();
                String type = null;
                type = getInputSchema().getElementTypeByName(elementStack);

                if (ARRAY_ELEMENT_TYPE.equals(type)) {
                    try {
                        sendArrayStartEvent(stackElement.getName());
                    } catch (JSException e) {
                        log.error("Error occurred while processing start array event", e);
                    }
                } else {
                    log.error("Found an array in the payload but schema doesn't specify any array of type " + type);
                    return false;
                }
            }
        } catch (InvalidPayloadException e) {
            log.error(e.getMessage(), e);
        }
        return true;
    }

    @Override
    public boolean endArray() throws ParseException, IOException {
        if (!getDmEventStack().isEmpty()) {
            DMReaderEvent stackElement = getDmEventStack().peek();
            String type = null;
            try {
                type = getInputSchema().getElementTypeByName(elementStack);
            } catch (InvalidPayloadException e) {
                log.error(e.getMessage(), e);
            }
            if (ARRAY_ELEMENT_TYPE.equals(type)) {
                try {
                    sendArrayEndEvent(stackElement.getName());
                } catch (JSException e) {
                    log.error("Error occurred while processing end array event", e);
                }
            } else {
                log.error("Array element not found " + type);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean primitive(Object value) throws ParseException, IOException {
        if (!getDmEventStack().isEmpty()) {
            DMReaderEvent stackElement = getDmEventStack().peek();
            try {
                String fieldType = getFieldType(value);
                sendFieldEvent(stackElement.getName(), value, fieldType);
            } catch (JSException e) {
                log.error("Error while sending field value ");
            }
        }
        return true;
    }

    private String getFieldType(Object value) {
        if (value instanceof String) {
            return STRING_ELEMENT_TYPE;
        } else if (value instanceof Integer || value instanceof Long) {
            return INTEGER_ELEMENT_TYPE;
        } else if (value instanceof Double || value instanceof Float) {
            return NUMBER_ELEMENT_TYPE;
        } else if (value instanceof Boolean) {
            return BOOLEAN_ELEMENT_TYPE;
        }
        throw new IllegalArgumentException("Unsupported value type found" + value.toString());
    }

    public InputModelBuilder getModelBuilder() {
        return modelBuilder;
    }

    private void sendFieldEvent(String fieldName, Object value, String type) throws IOException, JSException {
        DMReaderEvent fieldEvent = new DMReaderEvent(ReaderEventTypes.EventType.FIELD,
                fieldName, value, type);
        getModelBuilder().notifyEvent(fieldEvent);
    }

    private void pushObjectStartEvent(String fieldName) throws IOException, JSException {
        DMReaderEvent objectStartEvent = new DMReaderEvent(ReaderEventTypes.EventType.OBJECT_START,
                fieldName, null);
        dmEventStack.push(objectStartEvent);
    }

    private void sendObjectStartEvent(String fieldName) throws IOException, JSException {
        DMReaderEvent objectStartEvent = new DMReaderEvent(ReaderEventTypes.EventType.OBJECT_START,
                fieldName, null);
        getModelBuilder().notifyEvent(objectStartEvent);
        dmEventStack.push(objectStartEvent);
    }

    private void sendObjectEndEvent(String fieldName) throws IOException, JSException, InvalidPayloadException {
        DMReaderEvent objectEndEvent = new DMReaderEvent(ReaderEventTypes.EventType.OBJECT_END,
                fieldName, null);
        getModelBuilder().notifyEvent(objectEndEvent);
        if (fieldName != null) {
            if (!ARRAY_ELEMENT_TYPE.equals(getInputSchema().getElementTypeByName(elementStack))) {
                dmEventStack.pop();
            }
        } else {
            dmEventStack.pop();
        }
    }

    private void popObjectEndEvent(String fieldName) throws IOException, JSException, InvalidPayloadException {
        DMReaderEvent objectEndEvent = new DMReaderEvent(ReaderEventTypes.EventType.OBJECT_END,
                fieldName, null);
        if (!ARRAY_ELEMENT_TYPE.equals(getInputSchema().getElementTypeByName(elementStack))) {
            dmEventStack.pop();
        }
    }

    private void sendArrayStartEvent(String fieldName) throws IOException, JSException {
        DMReaderEvent arrayStartEvent = new DMReaderEvent(ReaderEventTypes.EventType.ARRAY_START,
                fieldName, null);
        getModelBuilder().notifyEvent(arrayStartEvent);
        dmEventStack.push(arrayStartEvent);
    }

    private void sendArrayEndEvent(String fieldName) throws IOException, JSException {
        DMReaderEvent arrayEndEvent = new DMReaderEvent(ReaderEventTypes.EventType.ARRAY_END,
                fieldName, null);
        getModelBuilder().notifyEvent(arrayEndEvent);
        dmEventStack.pop();
    }

    public Stack<DMReaderEvent> getDmEventStack() {
        return dmEventStack;
    }

    private void sendTerminateEvent() throws IOException, JSException {
        getModelBuilder().notifyEvent(new DMReaderEvent(ReaderEventTypes.EventType.TERMINATE,
                null, null));
        if (elementStack.size() != 1) {
            log.error("elementStack contain more than one value in the end : " + elementStack.size());
        } else {
            elementStack.remove(0);
        }
    }

    private void sendAnonymousObjectStartEvent() throws IOException, JSException {
        DMReaderEvent anonymousObjectStartEvent = new DMReaderEvent(ReaderEventTypes.EventType.ANONYMOUS_OBJECT_START,
                null, null);
        getModelBuilder().notifyEvent(anonymousObjectStartEvent);
        dmEventStack.push(anonymousObjectStartEvent);
    }
}