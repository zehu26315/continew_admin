/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
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

package top.continew.admin.training.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 通过本地子进程抽取图谱并回答图谱问题，与问答共用同一套 Python 环境。
 *
 * @author 工程实训小组
 * @since 4.2.0
 */
@Service
@RequiredArgsConstructor
class TrainingGraphWorker {

    private final ObjectMapper mapper;
    @Value("${TRAINING_GRAPH_PYTHON:}")
    private String python;
    @Value("${TRAINING_GRAPH_WORKER:}")
    private String script;
    @Value("${TRAINING_GRAPH_HOME:}")
    private String home;

    boolean configured() {
        return !python.isBlank() && !script.isBlank() && !home.isBlank();
    }

    JsonNode run(JsonNode req, int timeoutSeconds) {
        if (!configured()) {
            throw new BusinessException("请使用工程启动脚本启用本地图谱功能");
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(python, "-I", "-B", "-X", "utf8", script);
            builder.environment().put("TRAINING_GRAPH_HOME", home);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            var output = process.getInputStream();
            var result = CompletableFuture.supplyAsync(() -> {
                try (output) {
                    return mapper.readTree(output);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            try (var input = process.getOutputStream()) {
                mapper.writeValue(input, req);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new BusinessException("本地图谱处理超时或失败，请重试");
            }
            JsonNode reply = result.join();
            if (reply == null || !reply.path("data").isObject()) {
                throw new BusinessException(
                    reply == null ? "图谱服务未响应" : reply.path("error").asText("处理失败"));
            }
            return reply.get("data");
        } catch (IOException e) {
            throw new BusinessException("无法启动本地图谱处理，请检查运行环境");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("本地图谱处理已中断");
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
