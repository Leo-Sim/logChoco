package com.example.leo.logChoco.service;

import com.example.leo.logChoco.config.LogChocoConfig;
import com.example.leo.logChoco.config.entity.ServerInfo;
import com.example.leo.logChoco.entity.log.InboundLog;
import com.example.leo.logChoco.entity.log.LogInfo;
import com.example.leo.logChoco.inbound.InboundHandler;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Sinks;
import reactor.netty.tcp.TcpServer;
import reactor.netty.udp.UdpServer;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * Class that has business logic for receiving log
 * */

@Service
@RequiredArgsConstructor
public class InboundService {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private final PatternInfoService patternInfoService;
    private final LogChocoConfig logChocoConfig;
    private List<ServerInfo> servers;
    private Sinks.Many<LogInfo> nextSink;

    @PostConstruct
    public void init() {
        servers = logChocoConfig.getReceiveServer();
        nextSink = patternInfoService.getSink();
        runServers();
    }

    private void runServers() {
        servers.stream()
                .filter(server -> server.getPort() > 0 && StringUtils.hasText(server.getProtocol().name()))
                .forEach(server -> {
                    switch(server.getProtocol()) {
                        case TCP -> runTcpServer(server);
                        case UDP -> runUdpServer(server);
                        case TLS -> runTlsServer(server);
                        default -> logger.error("Failed to run servers for {}. Protocol is not supported");
                    }
                    
                });
    }

    private void runTcpServer(ServerInfo server) {
        TcpServer.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
            .port(server.getPort())
            .handle((in, out) -> in.receive().then())
            .doOnChannelInit((observer, channel, remoteAddress) -> {
                channel.pipeline().addFirst(new InboundHandler(nextSink));
                channel.pipeline().addFirst(new DelimiterBasedFrameDecoder(20 * 1024, Delimiters.lineDelimiter()));
            })
            .bind().subscribe(con -> {
                logger.info("#### Open TCP port {} for inbound logs", server.getPort());
            });
    }

    private void runUdpServer(ServerInfo server) {

        UdpServer.create()
                .host("localhost")
                .port(20002)
                .handle((in, out) -> in.receive().then())
                .bind().subscribe(con -> {
                    logger.info("#### Open UDP port {} for inbound logs", server.getPort());
                });
    }

    private void runTlsServer(ServerInfo server) {

    }


}
