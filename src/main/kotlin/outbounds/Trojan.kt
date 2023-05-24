package outbounds


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import model.RELAY_HANDLER_NAME
import model.config.Outbound
import model.config.TrojanSetting
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import stream.RelayInboundHandler
import stream.Surfer
import utils.Sha224Utils

object Trojan : outbounds.Outbound {
    fun outbound(
        originCTX: ChannelHandlerContext,
        outbound: Outbound,
        destAddrType: Byte,
        destAddr: String,
        destPort: Int,
        connectSuccess: (Channel) -> ChannelFuture,
        connectFail: () -> Unit,
        firstPackage: Boolean = true
    ) {
        val connectListener = FutureListener<Channel> { future ->
            val outboundChannel = future.now
            if (future.isSuccess) {
                connectSuccess(outboundChannel).also { channelFuture ->
                    channelFuture.addListener(ChannelFutureListener {
                        outboundChannel.pipeline().addLast(RELAY_HANDLER_NAME, RelayInboundHandler(originCTX.channel()))
                        originCTX.pipeline().addLast(
                            RELAY_HANDLER_NAME, TrojanRelayInboundHandler(
                                outboundChannel,
                                outbound.trojanSetting!!,
                                TrojanRequest(Socks5CommandType.CONNECT.byteValue(), destAddrType, destAddr, destPort),
                                firstPackage = firstPackage
                            )
                        )
                    })
                }
            } else {
                connectFail()
            }
        }
        Surfer.outbound(
            outbound = outbound, connectListener = connectListener, eventLoopGroup = originCTX.channel().eventLoop()
        )
    }
}

class TrojanRelayInboundHandler(
    relayChannel: Channel,
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest,
    inActiveCallBack: () -> Unit = {},
    private var firstPackage: Boolean = true
) : RelayInboundHandler(relayChannel, inActiveCallBack) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Trojan protocol only need package once, then send origin data directly
     */

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (firstPackage) {
            when (msg) {
                is ByteBuf -> {
                    val trojanPackage = byteBuf2TrojanPackage(msg, trojanSetting, trojanRequest)
                    ReferenceCountUtil.release(msg)
                    super.channelRead(ctx, TrojanPackage.toByteBuf(trojanPackage))
                    firstPackage = false
                }

                else -> {
                    logger.error("TrojanRelayHandler receive unknown message:${msg.javaClass.name}")
                    super.channelRead(ctx, msg)
                }
            }
        } else {
            super.channelRead(ctx, msg)
        }
    }

}

fun byteBuf2TrojanPackage(msg: ByteBuf, trojanSetting: TrojanSetting, trojanRequest: TrojanRequest): TrojanPackage {
    val currentAllBytes = ByteArray(msg.readableBytes())
    msg.readBytes(currentAllBytes)
    return TrojanPackage(
        Sha224Utils.encryptAndHex(trojanSetting.password), trojanRequest, ByteBufUtil.hexDump(currentAllBytes)
    )
}
