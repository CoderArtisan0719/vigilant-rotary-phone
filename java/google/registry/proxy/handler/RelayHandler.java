// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.proxy.handler;

import static google.registry.proxy.Protocol.PROTOCOL_KEY;

import com.google.common.flogger.FluentLogger;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.Deque;
import java.util.Queue;
import javax.inject.Inject;

/**
 * Receives inbound massage of type {@code I}, and writes it to the {@code relayChannel} stored in
 * the inbound channel's attribute.
 */
public class RelayHandler<I> extends SimpleChannelInboundHandler<I> {

  /**
   * A queue that saves messages that failed to be relayed.
   *
   * <p>This queue is null for channels that should not retry on failure, i. e. backend channels.
   *
   * <p>This queue does not need to be synchronised because it is only accessed by the I/O thread of
   * the channel, or its relay channel. Since both channels use the same EventLoop, their I/O
   * activities are handled by the same thread.
   */
  public static final AttributeKey<Deque<Object>> RELAY_BUFFER_KEY =
      AttributeKey.valueOf("RELAY_BUFFER_KEY");

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Key used to retrieve the relay channel from a {@link Channel}'s {@link Attribute}. */
  public static final AttributeKey<Channel> RELAY_CHANNEL_KEY =
      AttributeKey.valueOf("RELAY_CHANNEL");

  public RelayHandler(Class<? extends I> clazz) {
    super(clazz, false);
  }

  /** Read message of type {@code I}, write it as-is into the relay channel. */
  @Override
  protected void channelRead0(ChannelHandlerContext ctx, I msg) throws Exception {
    Channel channel = ctx.channel();
    Channel relayChannel = channel.attr(RELAY_CHANNEL_KEY).get();
    if (relayChannel == null) {
      logger.atSevere().log("Relay channel not specified for channel: %s", channel);
      ChannelFuture unusedFuture = channel.close();
    } else {
      writeToRelayChannel(channel, relayChannel, msg, false);
    }
  }

  public static void writeToRelayChannel(
      Channel channel, Channel relayChannel, Object msg, boolean retry) {
    ChannelFuture unusedFuture =
        relayChannel
            .writeAndFlush(msg)
            .addListener(
                future -> {
                  if (!future.isSuccess()) {
                    // TODO (jianglai): do not log the message once retry behavior is confirmed.
                    logger.atWarning().withCause(future.cause()).log(
                        "Relay failed: %s --> %s\nINBOUND: %s\nOUTBOUND: %s\nMESSAGE: %s",
                        channel.attr(PROTOCOL_KEY).get().name(),
                        relayChannel.attr(PROTOCOL_KEY).get().name(),
                        channel,
                        relayChannel,
                        msg);
                    // If we cannot write to the relay channel and the originating channel has
                    // a relay buffer (i. e. we tried to relay the frontend to the backend), store
                    // the message in the buffer for retry later. The relay channel (backend) should
                    // be killed (if it is not already dead, usually the relay is unsuccessful
                    // because the connection is closed), and a new backend channel will re-connect
                    // as long as the frontend channel is open. Otherwise, we are relaying from the
                    // backend to the frontend, and this relay failure cannot be recovered from: we
                    // should just kill the relay (frontend) channel, which in turn will kill the
                    // backend channel. It is fine to just save the message object itself, not a
                    // clone of it, because if the relay is not successful, its content is not read,
                    // therefore its buffer is not cleared.
                    Queue<Object> relayBuffer = channel.attr(RELAY_BUFFER_KEY).get();
                    if (relayBuffer != null) {
                      channel.attr(RELAY_BUFFER_KEY).get().add(msg);
                    }
                    ChannelFuture unusedFuture2 = relayChannel.close();
                  } else if (retry) {
                    // TODO (jianglai): do not log the message once retry behavior is confirmed.
                    logger.atInfo().log(
                        "Relay retry succeeded: %s --> %s\nINBOUND: %s\nOUTBOUND: %s\nsMESSAGE: %s",
                        channel.attr(PROTOCOL_KEY).get().name(),
                        relayChannel.attr(PROTOCOL_KEY).get().name(),
                        channel,
                        relayChannel,
                        msg);
                  }
                });
  }

  /** Specialized {@link RelayHandler} that takes a {@link FullHttpRequest} as inbound payload. */
  public static class FullHttpRequestRelayHandler extends RelayHandler<FullHttpRequest> {
    @Inject
    public FullHttpRequestRelayHandler() {
      super(FullHttpRequest.class);
    }
  }

  /** Specialized {@link RelayHandler} that takes a {@link FullHttpResponse} as inbound payload. */
  public static class FullHttpResponseRelayHandler extends RelayHandler<FullHttpResponse> {
    @Inject
    public FullHttpResponseRelayHandler() {
      super(FullHttpResponse.class);
    }
  }
}
