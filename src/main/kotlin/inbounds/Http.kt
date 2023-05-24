package inbounds


import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.handler.stream.ChunkedWriteHandler
import model.config.Inbound
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import outbounds.GalaxyOutbound
import outbounds.Trojan
import outbounds.byteBuf2TrojanPackage
import route.Route
import utils.SurferUtils
import java.net.URI


class HttpProxyServerHandler(private val inbound: Inbound) : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelRead(originCTX: ChannelHandlerContext, msg: Any) {
        //http proxy and http connect method
        if (msg is HttpRequest) {
            logger.info(
                "http inbound: [{}], method: {}, uri: {}",
                originCTX.channel().id().asShortText(),
                msg.method(),
                msg.uri()
            )
            if (msg.method() == HttpMethod.CONNECT) {
                tunnelProxy(originCTX, msg)
            } else {
                httpProxy(originCTX, msg)
            }
        } else {
            //other message
        }
    }

    private fun httpProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        val uri = URI(request.uri())
        val resolveOutbound = Route.resolveOutbound(inbound)

        val port = when (uri.port) {
            -1 -> 80
            else -> uri.port
        }
        logger.debug("http proxy outbound from {}, content: {}", originCTX.channel().id().asShortText(), request)
        resolveOutbound.ifPresent { outbound ->
            when (outbound.protocol) {
                "galaxy" -> {
                    GalaxyOutbound.outbound(originCTX, outbound, uri.host, port, {
                        // If you want to implement http capture, to code right here
                        it.pipeline().addFirst(
                            HttpClientCodec(),
                            HttpContentDecompressor(),
                            ChunkedWriteHandler(),
                        )
                        it.writeAndFlush(request)
                    }, {
                        originCTX.close()
                    })
                }

                "trojan" -> {
                    Trojan.outbound(originCTX,
                        outbound,
                        SurferUtils.getAddressType(uri.host).byteValue(),
                        uri.host,
                        port,
                        {
                            // If you want to implement http capture, to code right here

                            val ch = EmbeddedChannel(HttpRequestEncoder())
                            ch.writeOutbound(request)
                            val encoded = ch.readOutbound<ByteBuf>()
                            ch.close()

                            it.writeAndFlush(
                                TrojanPackage.toByteBuf(
                                    byteBuf2TrojanPackage(
                                        encoded, outbound.trojanSetting!!, TrojanRequest(
                                            Socks5CommandType.CONNECT.byteValue(),
                                            SurferUtils.getAddressType(uri.host).byteValue(),
                                            uri.host,
                                            port
                                        )
                                    )
                                )
                            )
                        },
                        {
                            originCTX.close()
                        },
//                        firstPackage = false
                    )
                }

                else -> {
                    logger.error(
                        "[${
                            originCTX.channel().id().asShortText()
                        }], protocol=${outbound.protocol} not support"
                    )
                }
            }
        }

    }

    private fun tunnelProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        val uri = URI(
            if (request.uri().startsWith("https://")) {
                request.uri()
            } else {
                "https://${request.uri()}"
            }
        )
        val resolveOutbound = Route.resolveOutbound(inbound)

        val port = when (uri.port) {
            -1 -> 443
            else -> uri.port
        }
        resolveOutbound.ifPresent { outbound ->
            when (outbound.protocol) {
                "galaxy" -> {
                    GalaxyOutbound.outbound(originCTX, outbound, uri.host, port, {
                        //write Connection Established
                        originCTX.writeAndFlush(
                            DefaultHttpResponse(
                                request.protocolVersion(),
                                HttpResponseStatus(HttpResponseStatus.OK.code(), "Connection established"),
                            )
                        ).also {
                            //remove all listener
                            val pipeline = originCTX.pipeline()
                            while (pipeline.first() != null) {
                                pipeline.removeFirst()
                            }
                        }
                    }, {
                        //todo: When the remote cannot be connected, the origin needs to be notified correctly
                        logger.warn { "from id: ${originCTX.channel().id().asShortText()}, connect to remote fail" }
                    })
                }

                "trojan" -> {
                    Trojan.outbound(originCTX,
                        outbound,
                        SurferUtils.getAddressType(uri.host).byteValue(),
                        uri.host,
                        port,
                        {
                            //write Connection Established
                            originCTX.writeAndFlush(
                                DefaultHttpResponse(
                                    request.protocolVersion(),
                                    HttpResponseStatus(HttpResponseStatus.OK.code(), "Connection established"),
                                )
                            ).also {
                                //remove all listener
                                val pipeline = originCTX.pipeline()
                                while (pipeline.first() != null) {
                                    pipeline.removeFirst()
                                }
                            }
                        },
                        {
                            originCTX.close()
                        })
                }

                else -> {
                    logger.error(
                        "id: ${
                            originCTX.channel().id().asShortText()
                        }, protocol=${outbound.protocol} not support"
                    )
                }
            }
        }
    }
}
