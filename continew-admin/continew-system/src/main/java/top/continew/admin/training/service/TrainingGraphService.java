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
import org.springframework.stereotype.Service;
import top.continew.admin.training.service.TrainingDocumentService.DocumentInfo;
import top.continew.starter.core.exception.BusinessException;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 图谱问答：只使用当前账号可读、且图谱已就绪的资料版本。
 *
 * @author 工程实训小组
 * @since 4.2.0
 */
@Service
@RequiredArgsConstructor
public class TrainingGraphService {

    private final TrainingDocumentService documents;
    private final TrainingGraphWorker worker;
    private final ObjectMapper mapper;
    private final Semaphore slot = new Semaphore(1);

    /**
     * 沿实体与关系回答跨段落的问题。
     *
     * @param question 问题
     * @return 答案、证据、命中的节点关系与路径
     */
    public JsonNode ask(String question) {
        if (question == null || question.isBlank() || question.length() > 500) {
            throw new BusinessException("请输入 1 至 500 字的问题");
        }
        var req = mapper.createObjectNode().put("op", "ask").put("question", question.trim());
        var ready = req.putArray("documents");
        for (DocumentInfo doc : documents.list()) {
            if ("ready".equals(doc.graphState())) {
                ready.addObject().put("documentId", doc.documentId()).put("version", doc.version())
                    .put("fileName", doc.fileName());
            }
        }
        if (ready.isEmpty()) {
            throw new BusinessException("暂无可检索的资料图谱，请查看图谱状态");
        }
        return run(req, 240);
    }

    /**
     * 返回单份资料当前版本的完整图谱，供前端展示节点与关系。
     *
     * @param id 资料编号
     * @param version 资料版本
     * @return 节点与关系
     */
    public JsonNode graph(String id, int version) {
        List<DocumentInfo> matched = documents.list().stream()
            .filter(doc -> doc.documentId().equals(id) && doc.version() == version).toList();
        if (matched.isEmpty()) {
            throw new BusinessException("资料不可访问或版本已更新，请刷新列表");
        }
        if (!"ready".equals(matched.get(0).graphState())) {
            throw new BusinessException("图谱尚未就绪，请查看图谱状态");
        }
        var req = mapper.createObjectNode().put("op", "graph").put("documentId", id)
            .put("version", version).put("fileName", matched.get(0).fileName());
        return run(req, 60);
    }

    private JsonNode run(JsonNode req, int timeoutSeconds) {
        if (!slot.tryAcquire()) {
            throw new BusinessException("本地图谱正在处理其他请求，请稍后重试");
        }
        try {
            return worker.run(req, timeoutSeconds);
        } finally {
            slot.release();
        }
    }
}
