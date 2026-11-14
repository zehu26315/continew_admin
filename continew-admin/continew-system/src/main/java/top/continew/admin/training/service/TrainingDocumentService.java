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

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 私人实训资料。所有读写均使用当前会话的用户和租户。
 *
 * @author 工程实训小组
 * @since 4.2.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrainingDocumentService {

    private static final String SCOPE = " WHERE tenant_id = ? AND owner_id = ?";
    private static final String CURRENT_DOCUMENT = SCOPE + " AND id = ? AND version = ?";
    private static final Set<String> EXTENSIONS = Set.of("txt", "pdf", "docx");
    private final JdbcTemplate jdbc;
    @Value("${TRAINING_RAG_HOME:}")
    private String ragHome;
    @Value("${TRAINING_GRAPH_HOME:}")
    private String graphHome;

    String currentName(String id, int version) {
        List<String> names = jdbc.query(
            "SELECT file_name FROM training_document" + CURRENT_DOCUMENT,
            (row, index) -> row.getString(1), tenantId(), StpUtil.getLoginIdAsLong(), id, version);
        requireCurrent(names.size());
        return names.get(0);
    }

    void discardIndex(String id, int version) {
        discard(ragHome, "index", id, version, "索引");
    }

    void discardGraph(String id, int version) {
        discard(graphHome, "", id, version, "图谱");
    }

    private void discard(String home, String dir, String id, int version, String label) {
        if (home.isBlank() || !id.matches("[0-9a-f-]{36}")) {
            return;
        }
        Path file = dir.isBlank() ? Path.of(home, id + "-v" + version + ".sqlite3")
            : Path.of(home, dir, id + "-v" + version + ".sqlite3");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("旧版资料{}暂时无法清理", label);
        }
    }

    public record DocumentInfo(String documentId, int version, String fileName, int fileSize,
        LocalDateTime updatedAt, String indexState, String indexError, String graphState,
        String graphError) {
    }

    /**
     * 列出当前用户的资料元数据。
     *
     * @return 资料列表
     */
    public List<DocumentInfo> list() {
        return jdbc.query(
            "SELECT id, version, file_name, file_size, updated_at, index_state, index_error,"
                + " graph_state, graph_error FROM training_document" + SCOPE
                + " ORDER BY updated_at DESC, id",
            (row, index) -> new DocumentInfo(row.getString("id"), row.getInt("version"),
                row.getString("file_name"), row.getInt("file_size"),
                row.getTimestamp("updated_at").toLocalDateTime(), row.getString("index_state"),
                row.getString("index_error"), row.getString("graph_state"),
                row.getString("graph_error")),
            tenantId(), StpUtil.getLoginIdAsLong());
    }

    /**
     * 新建资料，正文与元数据一起写入，初始版本为 1。
     *
     * @param file 上传文件
     * @return 资料编号
     * @throws IOException 文件读取失败
     */
    public String create(MultipartFile file) throws IOException {
        String name = validateFile(file);
        String id = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO training_document (id, tenant_id, owner_id, version, file_name, file_size, file_content)"
                + " VALUES (?, ?, ?, 1, ?, ?, ?)",
            id, tenantId(), StpUtil.getLoginIdAsLong(), name,
            file.getSize(), file.getBytes());
        return id;
    }

    /**
     * 原子替换当前版本，过期的编辑不能覆盖新内容。
     *
     * @param id 资料编号
     * @param version 替换前的版本
     * @param file 新文件
     * @throws IOException 文件读取失败
     */
    public void replace(String id, int version, MultipartFile file) throws IOException {
        String name = validateFile(file);
        int count = jdbc.update(
            "UPDATE training_document SET file_name = ?, file_size = ?, file_content = ?,"
                + " index_state = 'pending', index_error = NULL, graph_state = 'pending', graph_error = NULL,"
                + " version = version + 1, updated_at = CURRENT_TIMESTAMP" + CURRENT_DOCUMENT,
            name, file.getSize(), file.getBytes(), tenantId(), StpUtil.getLoginIdAsLong(), id,
            version);
        requireCurrent(count);
        discardIndex(id, version);
        discardGraph(id, version);
    }

    /**
     * 读取指定版本，旧版本和其他用户的资料均不可见。
     *
     * @param id 资料编号
     * @param version 请求版本
     * @return 文件内容
     */
    public byte[] read(String id, int version) {
        List<byte[]> results =
            jdbc.query("SELECT file_content FROM training_document" + CURRENT_DOCUMENT,
                (row, index) -> row.getBytes("file_content"), tenantId(),
                StpUtil.getLoginIdAsLong(), id, version);
        requireCurrent(results.size());
        return results.get(0);
    }

    /**
     * 删除自己的当前版本及其问答索引。
     *
     * @param id 资料编号
     * @param version 资料版本
     */
    public void delete(String id, int version) {
        requireCurrent(jdbc.update("DELETE FROM training_document" + CURRENT_DOCUMENT, tenantId(),
            StpUtil.getLoginIdAsLong(), id, version));
        discardIndex(id, version);
        discardGraph(id, version);
    }

    /**
     * 将本人的当前资料重新加入索引队列。
     *
     * @param id 资料编号
     * @param version 文件版本
     */
    public void retry(String id, int version) {
        currentName(id, version);
        jdbc.update(
            "UPDATE training_document SET index_state = 'pending', index_error = NULL,"
                + " graph_state = 'pending', graph_error = NULL" + CURRENT_DOCUMENT,
            tenantId(), StpUtil.getLoginIdAsLong(), id, version);
    }

    private static long tenantId() {
        if (!TenantContextHolder.isTenantEnabled()) {
            return 0L;
        }
        Long tenant = UserContextHolder.getExtraContext().getTenantId();
        if (tenant == null) {
            throw new BusinessException("登录身份缺少租户信息，请重新登录");
        }
        return tenant;
    }

    private static String validateFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 180
            || name.matches("(?s).*[\\\\/\\p{Cntrl}].*")) {
            throw new BusinessException("资料名称无效");
        }
        int dot = name.lastIndexOf('.');
        if (dot < 1 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new BusinessException("请选择 PDF、DOCX 或 TXT 文件");
        }
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024) {
            throw new BusinessException("资料不能为空，单份资料上限为 5 MB");
        }
        return name;
    }

    private static void requireCurrent(int count) {
        if (count != 1) {
            throw new BusinessException("资料不可访问或版本已更新，请刷新列表");
        }
    }
}
