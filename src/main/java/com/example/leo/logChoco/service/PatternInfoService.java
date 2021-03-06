package com.example.leo.logChoco.service;

import com.example.leo.logChoco.config.LogChocoConfig;
import com.example.leo.logChoco.config.entity.OutboundLogInfo;
import com.example.leo.logChoco.entity.BufferInfo;
import com.example.leo.logChoco.entity.log.LogInfo;
import com.example.leo.logChoco.regex.FieldType;
import com.example.leo.logChoco.entity.log.InboundLog;
import com.example.leo.logChoco.entity.ReadFieldInfo;
import com.example.leo.logChoco.exception.InvalidLogFormatException;
import com.example.leo.logChoco.format.LogFormatterFactory;
import com.example.leo.logChoco.regex.builder.AbstractRegexBuilder;
import com.example.leo.logChoco.regex.builder.RegexBuilderFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

// 설정파일에서 ${DATE}`${TIME_STAMP}`{NUMBER}`{BOOLEAN}
@Service
@RequiredArgsConstructor
public class PatternInfoService {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private final LogChocoConfig logChocoConfig;
    private final OutboundLogService outboundLogService;
    private final SettingService settingService;
    private final MonitorService monitorService;

    @Getter
    protected Sinks.Many<LogInfo> sink;

    // 모든 로그 포맷 정보 담고있는 리스트.
    @Getter
    private List<ReadFieldInfo> fieldInfoList;

    // Separater that divides key and value for each option.
    private final String DEFAULT_OPTION_KEY_VALUE_SEPERATOR = ":";
    // Separator that divides each option.
    private final String DEFAULT_OPTION_SEPARATOR = ",";

    @PostConstruct
    public void init() {
        initRegexSetting();

        sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<List<LogInfo>> flux = sink.asFlux().bufferTimeout(BufferInfo.BUFFER_SIZE, BufferInfo.BUFFER_DURATION_SECOND);
        flux.subscribe(consumeLogs());
    }

    /**
     * send signal to MonitorService to get data on inbound logs.
     * */
    private void sendSignalToMonitor(List<LogInfo> logs) {
        monitorService.getInboundSink().emitNext(logs, Sinks.EmitFailureHandler.FAIL_FAST);
    }

    /**
     * Consumer for inbound logs from inboundService.java
     * */
    private Consumer<List<LogInfo>> consumeLogs() {
        return logs -> {
            sendSignalToMonitor(logs);
            getFormattedLogText(logs);
        };
    }

    /**
     * Receive log text as a parameter.
     * Iterate fieldInfoList to check if the log matches any log pattern.
     * If it matches, return formatted log.
     * */
    private void getFormattedLogText(List<LogInfo> inboundLogList) {


        Flux<LogInfo> flux = Flux.fromStream(inboundLogList.stream());

        flux.doOnComplete(() -> {
            logger.debug("Change log format. size : {}", inboundLogList.size());
        }).subscribe(inboundLog -> {
            Optional<ReadFieldInfo> optional = fieldInfoList.stream()
                    .filter(info -> info.checkIfMatchLogRegex(inboundLog.getLog()))
                    .findFirst();

            if(optional.isPresent()) {
                ReadFieldInfo fieldInfo = optional.get();
                OutboundLogInfo outboundLogInfo = logChocoConfig.getOutboundLogInfo();

                String formattedLog = LogFormatterFactory.getFormatter(outboundLogInfo, fieldInfo, inboundLog).getFormattedLog();
                System.out.println("formatted : " + formattedLog);
//                outboundLogService.getSink().emitNext(formattedLog, Sinks.EmitFailureHandler.FAIL_FAST);
            }
        });
    }


    /**
     * Read setting file and set regex cache
     * when process starts
     * */
    private void initRegexSetting() {
        fieldInfoList = readPatternInfoFile();

        fieldInfoList.stream().forEach(fieldInfo -> {
            try {
                setRegexFormat(fieldInfo);
            } catch (InvalidLogFormatException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Method that  create regex format
     */
    private void setRegexFormat(ReadFieldInfo fieldInfo) throws InvalidLogFormatException {

        String separatorForValue = DEFAULT_OPTION_KEY_VALUE_SEPERATOR;
        String separatorForOption = DEFAULT_OPTION_SEPARATOR;

        String delimiter = fieldInfo.getDelimiter();
        List<String> formatList = fieldInfo.getFormat();
        String[] formats = formatList.toArray(new String[formatList.size()]);
        List<String> columList = fieldInfo.getColumns();


        if(formatList.size() < 1 || columList.size() < 1) {
            throw new InvalidLogFormatException("Check format, columns, delimiter in configuration file. The length of 'format' or 'columns' separated by delimiter is less than 1");
        }

        if(formatList.size() != columList.size()) {
            throw new InvalidLogFormatException("The length of format in configuration file should be same with the length of colums");
        }

        StringBuilder regexSb = new StringBuilder();
        IntStream.range(0, formats.length).forEach(i -> {
            String format = formats[i];
            try {
                String type = format;
                Map<String, String> optionMap = new HashMap<>();

                // Save each option for each columns into map.
                if(format.indexOf("(") > 0 && format.endsWith(")")) {
                    type = format.substring(0, format.indexOf("("));
                    String[] options = format.substring(format.indexOf("(") + 1, format.indexOf(")")).split(separatorForOption);

                    Arrays.stream(options).forEach(option -> {
                        String[] kv = option.split(separatorForValue,2 );

                        if(kv.length != 2) {
                            logger.warn("Wrong option for {}. each option must have key and value separated by {}", format, separatorForValue);
                            return;
                        }
                        optionMap.put(kv[0], kv[1]);
                    });
                }

                // Get regex builder according to field type. and add option to it.
                AbstractRegexBuilder builder = RegexBuilderFactory.getRegexBuilder(FieldType.valueOf(type));
                builder.addRegexOptions(optionMap);

                regexSb.append(builder.getValue()).append(delimiter);

            } catch (IllegalArgumentException e) {
                logger.error("Field type {} is not supported.  ", format);
                e.printStackTrace();
            }
        });

        regexSb.deleteCharAt(regexSb.lastIndexOf(delimiter));
        fieldInfo.setFormatInRegex(regexSb.toString());
    }


    private List<ReadFieldInfo> readPatternInfoFile() {

        String filePath = logChocoConfig.getFilePath();
        logger.info("Read configuration file -> {}", filePath);
        ObjectMapper mapper = new ObjectMapper();

        String formatSetting =  settingService.getFormatSetting();
        try {
            return mapper.readValue(formatSetting, new TypeReference<List<ReadFieldInfo>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }

    private boolean validatePattern(String input, String pattern) {
        pattern = "^" + pattern + "$";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(input);
        return m.matches();
    }
}
