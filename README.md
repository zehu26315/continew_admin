# ContiNew Admin 工程实训

三人小组的 Windows 开发仓库，对应《阶段 2 任务》：在 ContiNew Admin 上做人脸识别登录、私人资料 RAG 问答、资料图谱（GraphRAG）问答。后端在 `continew-admin`，前端在 `continew-admin-ui`。

## 任务现状

| 任务 | 状态 | 入口 |
|---|---|---|
| 任务一 人脸识别登录 | 已完成 | 个人中心 → 安全设置绑定/重录/解绑；登录页「人脸登录」支持指定账号与免输账号 |
| 任务二 私人资料 RAG 问答 | 已完成 | 工作台 → 我的资料；支持 TXT/PDF/DOCX 增删改、自动索引、带原文出处的问答 |
| 任务三 GraphRAG 图谱问答 | 已完成 | 同上页面「图谱」列与入口；问答弹窗可切换「向量检索 / 图谱检索」 |

任务三的图数据存在本地 SQLite 而非 Neo4j（本机无 Docker），接口与前端已与存储解耦，详见[任务三实现说明](任务3图谱问答实现说明.md)。

## 快速开始

```powershell
git clone https://github.com/zehu26315/continew_admin.git
cd continew_admin
powershell -ExecutionPolicy Bypass -File .\Start-Team.ps1 -Native -Graph
```

脚本自己处理 JDK 17 定位、全局 Node 定位、数据库连接参数、胖包构建与前后端拉起，成功后输出 `Ready: http://localhost:5173/`。默认账号 `admin / admin123`、`test / test123`。

首次运行前只需要准备好：**MySQL 8+ 里建好空库 `continew_admin` 与账号**、**带密码的 Redis**。建库 SQL、环境变量、参数、常见错误都在[启动说明](启动说明.md)。

### 环境要求

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | 17 | 脚本强制 17，命中 `JAVA_HOME` 或 scoop 的 `openjdk17` |
| Node | LTS（任意） | 用全局安装，脚本通过 `fnm env` 定位 |
| pnpm | 12.18.0 | 与 `continew-admin-ui/package.json` 的 `packageManager` 一致 |
| MySQL | 8.0 及以上 | 表由 Liquibase 首次启动时创建（15 个 changeset） |
| Redis | 7 及以上，必须带密码 | 应用读 `REDIS_PWD` |
| Python | 3.12（uv 管理） | 图谱/问答用 3.12；人脸要求 3.11/3.12；脚本自动定位，可用 `-Python` 覆盖 |

### 启动方式二选一

- **本机数据库（推荐）**：加 `-Native`，参数默认 `continew_admin/continew_admin@3306`、Redis `123456`，可用环境变量覆盖。
- **Docker**：先启动 Docker Desktop，去掉 `-Native`。脚本生成独立密码并起 MySQL（13306）与 Redis（16379）。

后端改动后加 `-Build` 重新打包；日志与私有运行数据都在本机 `local-runtime/`（已在 `.gitignore`）。

## 课程功能开关

| 参数 | 作用 | 前置条件 |
|---|---|---|
| `-Face` | 人脸注册、刷脸登录、重录解绑、录入质量指导 | 64 位 Python 3.11/3.12，默认用 `py`，可用 `-FacePython <path>` 指定 |
| `-Rag` | 私人资料向量索引与问答 | Ollama 与 `qwen2.5:3b`、`qwen3-embedding:0.6b`（约 2.5 GB） |
| `-Graph` | 资料图谱抽取与图谱问答 | 仅需 Python，脚本装 `pypdf`、`defusedxml`；有 Ollama 时自动改用模型抽取 |

三个开关可同时使用。未开启的功能不会启动对应后台队列，资料列表里相应状态会停在“建立中”。

演示资料在[任务2演示资料](任务2演示资料/)目录，操作步骤见[资料问答使用说明](任务2演示资料/资料问答使用说明.md)。Dify 工作流模板只是分支逻辑演示，运行主项目无需安装 Dify。

## 目录

```
continew-admin/            后端（Spring Boot 3.5 / Java 17，Maven 多模块）
  continew-system/         认证与业务：auth、training（资料、人脸、问答、图谱）
  continew-server/         启动类、配置、Liquibase 变更集
  training-face/           人脸识别 Python 子进程
  training-rag/            资料问答 Python 子进程
  training-graph/          图谱抽取与检索 Python 子进程
continew-admin-ui/         前端（Vue 3 / Vite 5 / Arco Design）
任务2演示资料/             演示用 TXT、PDF、DOCX 与说明
local-runtime/             本机运行时数据（日志、索引、虚拟环境），不入库
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [启动说明](启动说明.md) | 环境准备、建库、参数、停止、常见错误 |
| [修改说明](修改说明.md) | 本次改动清单、验证证据、回滚方式 |
| [任务3图谱问答实现说明](任务3图谱问答实现说明.md) | 图谱数据流、接口契约、前端说明、验收步骤 |
| [人脸识别模块需求说明](人脸识别模块需求说明.md) | 绑定时序、阈值、接口与隐私约定 |
| [任务2本地知识库问答开发计划](任务2本地知识库问答开发计划.md) | 检索与引用核验的实现思路 |
| [阶段2三人并行开发计划](阶段2三人并行开发计划.md) | 分工、约定、验收路径 |
| `continew-admin/docs/manual/training.mdx` | 实训工作台的功能手册 |

## 协作与提交

- 分支：从 `main` 切功能分支（如 `feature/face-login`），完成后合并回 `main`；课程主仓库通过 Pull Request 合入。
- 提交信息遵循 Conventional Commits，例如 `feat(training): 新增资料图谱问答`。
- 提交前跑对应门禁：后端 `mvnw verify`（Spotless、Checkstyle、单元测试），前端 `pnpm lint && pnpm typecheck && pnpm build`。
- 提交中较大部分由 AI 生成时，在 commit message 末尾加 `Assisted-by: <工具名>`。
- 远端：课程仓库 `xiaoxu-mygoing/continew-engineering-training`，个人仓库 `zehu26315/continew_admin`。

## 已知边界

- 人脸为单帧比对，无活体检测；阈值是实训初始值，需用组员真实样本校准。
- 未安装 Ollama 时，图谱走规则抽取、问答为抽取式回答，长文档跨段推理弱于模型路径。
- 图谱按资料独立建图，尚未做多文档图合并与“删除一份资料只撤销其证据”的精细回退。
- `pnpm install` 会用 pnpm 12 重写 `pnpm-lock.yaml`，这是大版本升级的固有行为，与业务代码无关。

本项目基于 [ContiNew Admin](https://github.com/continew-org/continew-admin) 和 [ContiNew Admin UI](https://github.com/continew-org/continew-admin-ui)，许可证保留在各自目录。
