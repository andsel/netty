/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.codec.mqtt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.internal.EmptyArrays;

import java.util.List;

import static io.netty.handler.codec.mqtt.MqttCodecUtil.*;

/**
 * Encodes Mqtt messages into bytes following the protocol specification v3.1
 * as described here <a href="http://public.dhe.ibm.com/software/dw/webservices/ws-mqtt/mqtt-v3r1.html">MQTTV3.1</a>
 */
@ChannelHandler.Sharable
public final class MqttEncoder extends MessageToMessageEncoder<MqttMessage> {

    static class PacketSection {
        private final int bufferSize;
        private final ByteBuf byteBuf;

        public PacketSection(int bufferSize, ByteBuf buf) {
            this.bufferSize = bufferSize;
            this.byteBuf = buf;
        }
    }

    public static final MqttEncoder INSTANCE = new MqttEncoder();

    private MqttEncoder() { }

    @Override
    protected void encode(ChannelHandlerContext ctx, MqttMessage msg, List<Object> out) throws Exception {
        out.add(doEncode(ctx.alloc(), msg));
    }

    /**
     * This is the main encoding method.
     * It's only visible for testing.
     *
     * @param byteBufAllocator Allocates ByteBuf
     * @param message MQTT message to encode
     * @return ByteBuf with encoded bytes
     */
    static ByteBuf doEncode(ByteBufAllocator byteBufAllocator, MqttMessage message) {

        switch (message.fixedHeader().messageType()) {
            case CONNECT:
                return encodeConnectMessage(byteBufAllocator, (MqttConnectMessage) message);

            case CONNACK:
                return encodeConnAckMessage(byteBufAllocator, (MqttConnAckMessage) message);

            case PUBLISH:
                return encodePublishMessage(byteBufAllocator, (MqttPublishMessage) message);

            case SUBSCRIBE:
                return encodeSubscribeMessage(byteBufAllocator, (MqttSubscribeMessage) message);

            case UNSUBSCRIBE:
                return encodeUnsubscribeMessage(byteBufAllocator, (MqttUnsubscribeMessage) message);

            case SUBACK:
                return encodeSubAckMessage(byteBufAllocator, (MqttSubAckMessage) message);

            case UNSUBACK:
            case PUBACK:
            case PUBREC:
            case PUBREL:
            case PUBCOMP:
                return encodeMessageWithOnlySingleByteFixedHeaderAndMessageId(byteBufAllocator, message);

            case PINGREQ:
            case PINGRESP:
            case DISCONNECT:
                return encodeMessageWithOnlySingleByteFixedHeader(byteBufAllocator, message);

            default:
                throw new IllegalArgumentException(
                        "Unknown message type: " + message.fixedHeader().messageType().value());
        }
    }

    private static ByteBuf encodeConnectMessage(
            ByteBufAllocator byteBufAllocator,
            MqttConnectMessage message) {

        MqttFixedHeader mqttFixedHeader = message.fixedHeader();
        MqttConnectVariableHeader variableHeader = message.variableHeader();

        // as MQTT 3.1 & 3.1.1 spec, If the User Name Flag is set to 0, the Password Flag MUST be set to 0
        if (!variableHeader.hasUserName() && variableHeader.hasPassword()) {
            throw new DecoderException("Without a username, the password MUST be not set");
        }

        PacketSection payloadSection = encodePayload(message, byteBufAllocator);

        // Variable header
        PacketSection variableHeaderSection = encodeVariableHeader(byteBufAllocator, variableHeader,
                payloadSection.bufferSize);
        int variablePartSize = variableHeaderSection.bufferSize + payloadSection.bufferSize;

        // Fixed header
        int fixedHeaderBufferSize = 1 + EncodersUtils.getVariableLengthInt(variablePartSize);
        ByteBuf buf = byteBufAllocator.buffer(fixedHeaderBufferSize + variablePartSize);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(mqttFixedHeader));

        buf.writeBytes(variableHeaderSection.byteBuf);

        // Payload
        buf.writeBytes(payloadSection.byteBuf);
        return buf;
    }

    private static PacketSection encodeVariableHeader(ByteBufAllocator byteBufAllocator,
                                                      MqttConnectVariableHeader variableHeader,
                                                      int payloadSize) {
        MqttVersion mqttVersion = MqttVersion.fromProtocolNameAndLevel(variableHeader.name(),
                (byte) variableHeader.version());

        PacketSection propertiesSection = encodeProperties(byteBufAllocator, variableHeader, mqttVersion);

        byte[] protocolNameBytes = mqttVersion.protocolNameBytes();
        int variableHeaderBufferSize = 2 + protocolNameBytes.length + 4;
        variableHeaderBufferSize += propertiesSection.bufferSize;
        int variablePartSize = variableHeaderBufferSize + payloadSize;

        ByteBuf buf = byteBufAllocator.buffer(variableHeaderBufferSize);
        EncodersUtils.writeVariableLengthInt(buf, variablePartSize);

        buf.writeShort(protocolNameBytes.length);
        buf.writeBytes(protocolNameBytes);

        buf.writeByte(variableHeader.version());
        buf.writeByte(EncodersUtils.getConnVariableHeaderFlag(variableHeader));
        buf.writeShort(variableHeader.keepAliveTimeSeconds());

        // write the properties
        buf.writeBytes(propertiesSection.byteBuf);

        return new PacketSection(variableHeaderBufferSize, buf);
    }


    private static PacketSection encodeProperties(ByteBufAllocator byteBufAllocator,
                                                  MqttConnectVariableHeader variableHeader,
                                                  MqttVersion mqttVersion) {
        ByteBuf propertiesHeaderBuf = byteBufAllocator.buffer();
        if (mqttVersion == MqttVersion.MQTT_5) {
            // encode also the Properties part
            MqttProperties mqttProperties = variableHeader.properties();
            ByteBuf propertiesBuf = byteBufAllocator.buffer();
            for (MqttProperties.MqttProperty property : mqttProperties.listAll()) {
                EncodersUtils.writeVariableLengthInt(propertiesBuf, property.propertyId);
                switch (property.propertyId) {
                    case 0x01: // Payload Format Indicator => Byte
                    case 0x17: // Request Problem Information
                    case 0x19: // Request Response Information
                    case 0x24: // Maximum QoS
                    case 0x25: // Retain Available
                    case 0x28: // Wildcard Subscription Available
                    case 0x29: // Subscription Identifier Available
                    case 0x2A: // Shared Subscription Available
                        final byte bytePropValue = ((MqttProperties.IntegerProperty) property).value.byteValue();
                        propertiesBuf.writeByte(bytePropValue);
                        break;
                    case 0x13: // Server Keep Alive => Two Byte Integer
                    case 0x21: // Receive Maximum
                    case 0x22: // Topic Alias Maximum
                    case 0x23: // Topic Alias
                        final short twoBytesInPropValue = ((MqttProperties.IntegerProperty) property).value.shortValue();
                        propertiesBuf.writeShort(twoBytesInPropValue);
                        break;
                    case 0x02: // Publication Expiry Interval => Four Byte Integer
                    case 0x11: // Session Expiry Interval
                    case 0x18: // Will Delay Interval
                    case 0x27: // Maximum Packet Size
                        final int fourBytesIntPropValue = ((MqttProperties.IntegerProperty) property).value;
                        propertiesBuf.writeInt(fourBytesIntPropValue);
                        break;
                    case 0x0B: // Subscription Identifier => Variable Byte Integer
                        final int vbi = ((MqttProperties.IntegerProperty) property).value;
                        EncodersUtils.writeVariableLengthInt(propertiesBuf, vbi);
                        break;
                    case 0x03: // Content Type => UTF-8 Encoded String
                    case 0x08: // Response Topic
                    case 0x12: // Assigned Client Identifier
                    case 0x15: // Authentication Method
                    case 0x1A: // Response Information
                    case 0x1C: // Server Reference
                    case 0x1F: // Reason String
                    case 0x26: // User Property
                        final String strPropValue = ((MqttProperties.StringProperty) property).value;
                        EncodersUtils.writeUTF8String(propertiesBuf, strPropValue);
                        break;
                    case 0x09: // Correlation Data => Binary Data
                    case 0x16: // Authentication Data
                        final byte[] binaryPropValue = ((MqttProperties.BinaryProperty) property).value;
                        propertiesBuf.writeShort(binaryPropValue.length);
                        propertiesBuf.writeBytes(binaryPropValue, 0, binaryPropValue.length);
                        break;
                }
            }
            EncodersUtils.writeVariableLengthInt(propertiesHeaderBuf, propertiesBuf.readableBytes());
            propertiesHeaderBuf.writeBytes(propertiesBuf);
        }

        int propertiesHeaderSize = propertiesHeaderBuf.readableBytes();
        return new PacketSection(propertiesHeaderSize, propertiesHeaderBuf);
    }

    private static PacketSection encodePayload(MqttConnectMessage message,
                                               ByteBufAllocator byteBufAllocator) {
        MqttConnectVariableHeader variableHeader = message.variableHeader();
        MqttVersion mqttVersion = MqttVersion.fromProtocolNameAndLevel(variableHeader.name(),
                (byte) variableHeader.version());
        MqttConnectPayload payload = message.payload();
        int payloadBufferSize = 0;
        // Client id
        String clientIdentifier = payload.clientIdentifier();
        if (!isValidClientId(mqttVersion, clientIdentifier)) {
            throw new MqttIdentifierRejectedException("invalid clientIdentifier: " + clientIdentifier);
        }
        byte[] clientIdentifierBytes = EncodersUtils.encodeStringUtf8(clientIdentifier);
        payloadBufferSize += 2 + clientIdentifierBytes.length;

        // Will topic and message
        String willTopic = payload.willTopic();
        byte[] willTopicBytes = willTopic != null ? EncodersUtils.encodeStringUtf8(willTopic) : EmptyArrays.EMPTY_BYTES;
        byte[] willMessage = payload.willMessageInBytes();
        byte[] willMessageBytes = willMessage != null ? willMessage : EmptyArrays.EMPTY_BYTES;
        if (variableHeader.isWillFlag()) {
            payloadBufferSize += 2 + willTopicBytes.length;
            payloadBufferSize += 2 + willMessageBytes.length;
        }

        String userName = payload.userName();
        byte[] userNameBytes = userName != null ? EncodersUtils.encodeStringUtf8(userName) : EmptyArrays.EMPTY_BYTES;
        if (variableHeader.hasUserName()) {
            payloadBufferSize += 2 + userNameBytes.length;
        }

        byte[] password = payload.passwordInBytes();
        byte[] passwordBytes = password != null ? password : EmptyArrays.EMPTY_BYTES;
        if (variableHeader.hasPassword()) {
            payloadBufferSize += 2 + passwordBytes.length;
        }

        // fill the buf with data
        ByteBuf paylodBuf = byteBufAllocator.buffer(payloadBufferSize);
        paylodBuf.writeShort(clientIdentifierBytes.length);
        paylodBuf.writeBytes(clientIdentifierBytes, 0, clientIdentifierBytes.length);
        if (variableHeader.isWillFlag()) {
            paylodBuf.writeShort(willTopicBytes.length);
            paylodBuf.writeBytes(willTopicBytes, 0, willTopicBytes.length);
            paylodBuf.writeShort(willMessageBytes.length);
            paylodBuf.writeBytes(willMessageBytes, 0, willMessageBytes.length);
        }
        if (variableHeader.hasUserName()) {
            paylodBuf.writeShort(userNameBytes.length);
            paylodBuf.writeBytes(userNameBytes, 0, userNameBytes.length);
        }
        if (variableHeader.hasPassword()) {
            paylodBuf.writeShort(passwordBytes.length);
            paylodBuf.writeBytes(passwordBytes, 0, passwordBytes.length);
        }

        return new PacketSection(payloadBufferSize, paylodBuf);
    }

    private static ByteBuf encodeConnAckMessage(
            ByteBufAllocator byteBufAllocator,
            MqttConnAckMessage message) {
        ByteBuf buf = byteBufAllocator.buffer(4);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(message.fixedHeader()));
        buf.writeByte(2);
        buf.writeByte(message.variableHeader().isSessionPresent() ? 0x01 : 0x00);
        buf.writeByte(message.variableHeader().connectReturnCode().byteValue());

        return buf;
    }

    private static ByteBuf encodeSubscribeMessage(
            ByteBufAllocator byteBufAllocator,
            MqttSubscribeMessage message) {
        int variableHeaderBufferSize = 2;
        int payloadBufferSize = 0;

        MqttFixedHeader mqttFixedHeader = message.fixedHeader();
        MqttMessageIdVariableHeader variableHeader = message.variableHeader();
        MqttSubscribePayload payload = message.payload();

        for (MqttTopicSubscription topic : payload.topicSubscriptions()) {
            String topicName = topic.topicName();
            byte[] topicNameBytes = EncodersUtils.encodeStringUtf8(topicName);
            payloadBufferSize += 2 + topicNameBytes.length;
            payloadBufferSize += 1;
        }

        int variablePartSize = variableHeaderBufferSize + payloadBufferSize;
        int fixedHeaderBufferSize = 1 + EncodersUtils.getVariableLengthInt(variablePartSize);

        ByteBuf buf = byteBufAllocator.buffer(fixedHeaderBufferSize + variablePartSize);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(mqttFixedHeader));
        EncodersUtils.writeVariableLengthInt(buf, variablePartSize);

        // Variable Header
        int messageId = variableHeader.messageId();
        buf.writeShort(messageId);

        // Payload
        for (MqttTopicSubscription topic : payload.topicSubscriptions()) {
            String topicName = topic.topicName();
            EncodersUtils.writeUTF8String(buf, topicName);
            buf.writeByte(topic.qualityOfService().value());
        }

        return buf;
    }

    private static ByteBuf encodeUnsubscribeMessage(
            ByteBufAllocator byteBufAllocator,
            MqttUnsubscribeMessage message) {
        int variableHeaderBufferSize = 2;
        int payloadBufferSize = 0;

        MqttFixedHeader mqttFixedHeader = message.fixedHeader();
        MqttMessageIdVariableHeader variableHeader = message.variableHeader();
        MqttUnsubscribePayload payload = message.payload();

        for (String topicName : payload.topics()) {
            byte[] topicNameBytes = EncodersUtils.encodeStringUtf8(topicName);
            payloadBufferSize += 2 + topicNameBytes.length;
        }

        int variablePartSize = variableHeaderBufferSize + payloadBufferSize;
        int fixedHeaderBufferSize = 1 + EncodersUtils.getVariableLengthInt(variablePartSize);

        ByteBuf buf = byteBufAllocator.buffer(fixedHeaderBufferSize + variablePartSize);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(mqttFixedHeader));
        EncodersUtils.writeVariableLengthInt(buf, variablePartSize);

        // Variable Header
        int messageId = variableHeader.messageId();
        buf.writeShort(messageId);

        // Payload
        for (String topicName : payload.topics()) {
            EncodersUtils.writeUTF8String(buf, topicName);
        }

        return buf;
    }

    private static ByteBuf encodeSubAckMessage(
            ByteBufAllocator byteBufAllocator,
            MqttSubAckMessage message) {
        int variableHeaderBufferSize = 2;
        int payloadBufferSize = message.payload().grantedQoSLevels().size();
        int variablePartSize = variableHeaderBufferSize + payloadBufferSize;
        int fixedHeaderBufferSize = 1 + EncodersUtils.getVariableLengthInt(variablePartSize);
        ByteBuf buf = byteBufAllocator.buffer(fixedHeaderBufferSize + variablePartSize);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(message.fixedHeader()));
        EncodersUtils.writeVariableLengthInt(buf, variablePartSize);
        buf.writeShort(message.variableHeader().messageId());
        for (int qos : message.payload().grantedQoSLevels()) {
            buf.writeByte(qos);
        }

        return buf;
    }

    private static ByteBuf encodePublishMessage(
            ByteBufAllocator byteBufAllocator,
            MqttPublishMessage message) {
        MqttFixedHeader mqttFixedHeader = message.fixedHeader();
        MqttPublishVariableHeader variableHeader = message.variableHeader();
        ByteBuf payload = message.payload().duplicate();

        String topicName = variableHeader.topicName();
        byte[] topicNameBytes = EncodersUtils.encodeStringUtf8(topicName);

        int variableHeaderBufferSize = 2 + topicNameBytes.length +
                (mqttFixedHeader.qosLevel().value() > 0 ? 2 : 0);
        int payloadBufferSize = payload.readableBytes();
        int variablePartSize = variableHeaderBufferSize + payloadBufferSize;
        int fixedHeaderBufferSize = 1 + EncodersUtils.getVariableLengthInt(variablePartSize);

        ByteBuf buf = byteBufAllocator.buffer(fixedHeaderBufferSize + variablePartSize);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(mqttFixedHeader));
        EncodersUtils.writeVariableLengthInt(buf, variablePartSize);
        buf.writeShort(topicNameBytes.length);
        buf.writeBytes(topicNameBytes);
        if (mqttFixedHeader.qosLevel().value() > 0) {
            buf.writeShort(variableHeader.messageId());
        }
        buf.writeBytes(payload);

        return buf;
    }

    private static ByteBuf encodeMessageWithOnlySingleByteFixedHeaderAndMessageId(
            ByteBufAllocator byteBufAllocator,
            MqttMessage message) {
        MqttFixedHeader mqttFixedHeader = message.fixedHeader();
        MqttMessageIdVariableHeader variableHeader = (MqttMessageIdVariableHeader) message.variableHeader();
        int msgId = variableHeader.messageId();

        int variableHeaderBufferSize = 2; // variable part only has a message id
        int fixedHeaderBufferSize = 1 + EncodersUtils.getVariableLengthInt(variableHeaderBufferSize);
        ByteBuf buf = byteBufAllocator.buffer(fixedHeaderBufferSize + variableHeaderBufferSize);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(mqttFixedHeader));
        EncodersUtils.writeVariableLengthInt(buf, variableHeaderBufferSize);
        buf.writeShort(msgId);

        return buf;
    }

    private static ByteBuf encodeMessageWithOnlySingleByteFixedHeader(
            ByteBufAllocator byteBufAllocator,
            MqttMessage message) {
        MqttFixedHeader mqttFixedHeader = message.fixedHeader();
        ByteBuf buf = byteBufAllocator.buffer(2);
        buf.writeByte(EncodersUtils.getFixedHeaderByte1(mqttFixedHeader));
        buf.writeByte(0);

        return buf;
    }
}
