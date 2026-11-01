package com.andrerinas.openheadunit.aap

import android.annotation.SuppressLint
import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.Message

/**
 * Answers the phone on the Bluetooth service channel. Once ServiceDiscoveryResponse advertises a
 * BluetoothService, the phone may send a pairing request there; an unanswered request must not be
 * left hanging. Wire format follows aap_protobuf (as used by aa-proxy-rs):
 * BluetoothPairingRequest { phone_address = 1; pairing_method = 2 },
 * BluetoothPairingResponse { status = 1 (MessageStatus, SUCCESS = 0); already_paired = 2 }.
 */
internal class AapControlBluetooth(
        private val aapTransport: AapTransport,
        private val context: Context) : AapControl {

    override fun execute(message: AapMessage): Int {
        if (message.type != PAIRING_REQUEST) {
            AppLog.i("Bluetooth channel: unhandled message type=${message.type} size=${message.size}")
            return 0
        }
        val request = message.parse(DynamicMessage.newBuilder(requestDescriptor)).build()
        val phoneAddress = request.getField(requestDescriptor.findFieldByName("phone_address")) as String
        val method = request.getField(requestDescriptor.findFieldByName("pairing_method"))
        val alreadyPaired = isBonded(phoneAddress)
        AppLog.i("Bluetooth pairing request: method=$method alreadyPaired=$alreadyPaired → replying SUCCESS")
        aapTransport.send(AapMessage(Channel.ID_BTH, PAIRING_RESPONSE, pairingResponse(alreadyPaired)))
        return 0
    }

    @SuppressLint("MissingPermission")
    private fun isBonded(address: String): Boolean = try {
        BluetoothHelper.getBluetoothAdapter(context)?.bondedDevices
            ?.any { it.address.equals(address, ignoreCase = true) } ?: false
    } catch (e: Exception) {
        AppLog.w("Bluetooth pairing: bonded-device lookup failed: ${e.message}")
        false
    }

    companion object {
        const val PAIRING_REQUEST = 32769
        const val PAIRING_RESPONSE = 32770
        private const val MESSAGE_STATUS_SUCCESS = 0

        private val messageTypes: List<Descriptors.Descriptor> by lazy {
            fun field(name: String, number: Int, type: FieldDescriptorProto.Type) =
                FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).build()
            val file = FileDescriptorProto.newBuilder()
                .setName("diauto_bluetooth_pairing.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("BluetoothPairingRequest")
                    .addField(field("phone_address", 1, FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(field("pairing_method", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                .addMessageType(DescriptorProto.newBuilder().setName("BluetoothPairingResponse")
                    .addField(field("status", 1, FieldDescriptorProto.Type.TYPE_INT32))
                    .addField(field("already_paired", 2, FieldDescriptorProto.Type.TYPE_BOOL)))
                .build()
            Descriptors.FileDescriptor.buildFrom(file, arrayOf()).messageTypes
        }
        private val requestDescriptor get() = messageTypes[0]
        private val responseDescriptor get() = messageTypes[1]

        internal fun pairingResponse(alreadyPaired: Boolean): Message =
            DynamicMessage.newBuilder(responseDescriptor)
                .setField(responseDescriptor.findFieldByName("status"), MESSAGE_STATUS_SUCCESS)
                .setField(responseDescriptor.findFieldByName("already_paired"), alreadyPaired)
                .build()
    }
}
