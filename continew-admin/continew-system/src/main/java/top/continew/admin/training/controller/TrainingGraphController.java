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

package top.continew.admin.training.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.training.service.TrainingGraphService;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 当前登录用户的资料图谱接口。
 *
 * @author 工程实训小组
 * @since 4.2.0
 */
@SaCheckLogin
@Log(ignore = true)
@RestController
@RequiredArgsConstructor
@RequestMapping("/training/graph")
public class TrainingGraphController {

    private final TrainingGraphService graph;

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    /**
     * 使用资料图谱回答跨段落问题。
     *
     * @param input 问题
     * @param response 响应
     * @return 答案、证据、节点、关系与路径
     */
    @PostMapping("/ask")
    public JsonNode ask(@RequestBody @Valid AskRequest input, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return graph.ask(input.question());
    }

    /**
     * 返回单份资料当前版本的节点与关系。
     *
     * @param id 资料编号
     * @param version 资料版本
     * @param response 响应
     * @return 图谱
     */
    @GetMapping("/{id}")
    public JsonNode documentGraph(@PathVariable String id, @RequestParam int version,
        HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return graph.graph(id, version);
    }
}
