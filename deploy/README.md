# 会晓 AI Test Deployment

`deploy` 目录只用于测试服部署，不是线上部署方案。

## 文件说明

- `docker-compose.test.yml`：测试服 Compose 配置，只把 API 暴露到宿主机 `28080`，ASR、AI 和 FunASR runtime 仅在 Docker 网络内访问。API 数据库通过 `HUIXIAO_DATABASE_URL` 连接共享远程数据库，不再启动测试服本地 PostgreSQL。
- `.env.example`：测试服环境变量模板。复制为 `.env` 后填入测试服密钥。
- `check_services.sh`：部署后检查 API、ASR、AI 和 FunASR runtime 是否可用。
- `funasr-runtime.Dockerfile`：测试服 FunASR runtime 容器，使用仓库内的项目改造版 `funasr_wss_server.py`。
- `deploy.sh`：给 Gitea runner 或测试服管理员调用的脚本。默认只做配置校验，不会 build、create、start、stop 或删除容器。所有会改变测试服状态的动作都必须通过 `HUIXIAO_DEPLOY_ACTION` 显式指定。

## 前置条件

测试服需要提前安装 Docker 和 Docker Compose 插件，并配置共享远程 `HUIXIAO_DATABASE_URL`、LLM API Key、短信密钥、支付宝应用密钥等运行密钥。LLM 和支付宝运行密钥必须来自 Gitea Secret 或服务器本地 `.env`，不要把真实 key 写进可提交文件。

当前 AI 主线固定为阿里云百炼 OpenAI 兼容接口：`HUIXIAO_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`、`HUIXIAO_LLM_MODEL=qwen3.7-plus`、`HUIXIAO_EMBEDDING_MODEL=text-embedding-v4`、`HUIXIAO_EMBEDDING_DIMENSIONS=1024`。`deploy.sh` 会拒绝旧白山/EdgeFn 地址、旧模型、旧 embedding、占位 key 或旧 ASR/Dolphin 环境变量。

当前测试服 API 只允许通过 `HUIXIAO_DATABASE_URL` 访问共享远程 MySQL/MariaDB。`deploy.sh` 默认不会删除历史 `deploy-postgres-1` 容器；只有显式设置 `HUIXIAO_DEPLOY_REMOVE_LEGACY_DB=true` 且执行 `build-create` 动作时才允许清理该旧容器。

支付宝 App 支付必须配置 `HUIXIAO_ALIPAY_APP_ID`、`HUIXIAO_ALIPAY_PRIVATE_KEY`、`HUIXIAO_ALIPAY_PUBLIC_KEY`、`HUIXIAO_ALIPAY_NOTIFY_URL` 和 `HUIXIAO_ALIPAY_GATEWAY_URL`。测试服容器不应引用本机 Windows 密钥路径；请在 `.env` 或 Gitea Secret 中写入单行密钥文本，回调地址使用测试服 API 地址，例如 `http://测试服IP:28080/api/v1/payments/alipay/notify`。

如果支付宝开放平台“开发设置”里启用了“接口内容加密方式”，测试服和线上 API 还必须配置同一把 `HUIXIAO_ALIPAY_AES_KEY`。该值是 Base64 AES 密钥，只能放在 `.env` 或 Gitea Secret 中，不要提交到仓库。启用后，服务端生成 App 支付订单串时会追加 `encrypt_type=AES`，并按支付宝 OpenAPI 要求先加密 `biz_content`，再对密文参数做 RSA2 签名。

向量模型切换后，旧 `knowledge_chunks` 索引不能继续混用。确认执行窗口后，在目标环境运行 `services/api_server/tools/rebuild_knowledge_index.py` 重建知识库索引；该脚本只删除并重建 `knowledge_chunks`，不删除会议结果、上传文件或任务记录。

当前实时 ASR 主线走阿里云百炼 `qwen3-asr-flash-realtime`，`asr_service` 通过 `HUIXIAO_LLM_API_KEY` 或 `HUIXIAO_ALIYUN_DASHSCOPE_API_KEY` 调用百炼 WebSocket。FunASR runtime 仍保留给文件转写、声纹抽取和离线说话人处理，不再承担实时字幕主链路。

当前 ASR 主线不再依赖测试机上的 `Dolphin/`、`Dolphin_poc/funasr_shim/` 或 `Dolphin_poc/models/base.cn/` 目录。FunASR/ModelScope 模型由容器运行时下载并缓存在 Docker volume 中：

```text
asr_model_cache
funasr_cache
```

不要为了排障清理这些 volume。清理后会重新下载大模型，容易拉长启动时间并制造新的不可控变量。

文件转写和声纹处理的 FunASR runtime 已纳入 `docker-compose.test.yml`，ASR 服务会通过 Docker 内网访问 `ws://funasr_runtime:10095`。不要在 `.env` 里另配外部 runtime 地址。

## 手动部署

```bash
cd /path/to/huixiao-ai/deploy
cp .env.example .env
# 编辑 .env，填入共享远程 HUIXIAO_DATABASE_URL、LLM API Key、短信配置等
HUIXIAO_DEPLOY_ACTION=validate bash deploy.sh
```

`deploy.sh` 默认等价于 `HUIXIAO_DEPLOY_ACTION=validate`：只校验 Compose、远程数据库、阿里云模型、旧 ASR 配置等发布前置条件，不改变任何容器状态。

需要重建容器时必须显式指定精确服务，脚本会先 build 目标镜像，再使用 `docker compose up --no-start --no-build --force-recreate --no-deps` 只重建目标容器，避免因为 Compose 依赖关系把其他容器顺手 create/recreate 或启动：

```bash
HUIXIAO_DEPLOY_ACTION=build-create HUIXIAO_DEPLOY_BUILD_SERVICES=api_server bash deploy.sh
HUIXIAO_DEPLOY_ACTION=build-create HUIXIAO_DEPLOY_BUILD_SERVICES=asr_service bash deploy.sh
HUIXIAO_DEPLOY_ACTION=build-create HUIXIAO_DEPLOY_BUILD_SERVICES=ai_service bash deploy.sh
HUIXIAO_DEPLOY_ACTION=build-create HUIXIAO_DEPLOY_BUILD_SERVICES=funasr_runtime bash deploy.sh
```

会员、支付、后台管理、数据库表结构这类 API 侧改动只需要重建 `api_server`。测试服务器会议容器暂时不能启动时，不要执行 `[start-huiyi]`，也不要重建或启动 `asr_service`、`funasr_runtime`、`ai_service`。

测试负责人确认槽位开放后，才允许显式启动容器。启动动作只执行 `docker compose start`，只启动已经存在的容器；不 build、不 create、不 recreate：

```bash
HUIXIAO_DEPLOY_ACTION=start \
  HUIXIAO_DEPLOY_UP_SERVICES="funasr_runtime asr_service ai_service api_server" \
  HUIXIAO_DEPLOY_ALLOW_HEAVY_START=true \
  HUIXIAO_DEPLOY_ALLOW_FULL_START=true \
  bash deploy.sh
```

测试服 API 地址：

```text
http://测试服IP或域名:28080/api/v1
ws://测试服IP或域名:28080/api/v1/live/ws
```

## Gitea 自动部署

在 Gitea Secrets 中配置完整的测试服 env 文件文本。优先级为 `HUIXIAO_TEST_ENV_FILE_OVERRIDE`、`HUIXIAO_TEST_ENV_FILE`、`HUIXIAO_TEST_ENV_PATH` 指向的服务器文件、服务器固定路径。仓库不再提交 `deploy/.env`，代码页里看到的文件不能作为运行配置来源。测试服 Gitea 只保留 `main` 分支，推送到 `main` 后 workflow 会在 runner 工作目录临时写入 `deploy/.env`。没有部署操作标记时只执行配置校验：

```bash
HUIXIAO_DEPLOY_ACTION=validate bash deploy/deploy.sh
```

Gitea push workflow 不再根据文件变更自动 build/create，也不会读取 Secret 中残留的旧启动开关决定动作。workflow 写入 `deploy/.env` 后会剔除旧 `HUIXIAO_DOLPHIN_*`、`HUIXIAO_FUNASR_SHIM_*`、`HUIXIAO_ASR_ASSET_*` 和 `HUIXIAO_ENABLE_LOCAL_DIARIZATION` 配置，避免历史模型挂载重新进入部署链路。`[stop-huiyi]` 和 `[status-huiyi]` 会跳过 env 写入，避免紧急停止被配置问题拦住。

Actions 日志会输出脱敏配置审计：支付宝私钥和支付宝公钥只显示 `sha256_16` 指纹和规范化后的长度，不打印密钥正文。管理员也可以调用 `/api/v1/admin/runtime-config` 查看当前 API 进程里的支付配置诊断结果，包括 AppID、回调地址、密钥指纹、私钥解析/自检和支付宝响应验签状态。

一次提交最多只能带一个部署操作标记：

```bash
[stop-huiyi]                # 只停止会晓四个容器
[status-huiyi]              # 只输出只读状态
[rebuild-api-no-start]      # 只 build/create api_server，不启动
[rebuild-asr-no-start]      # 只 build/create asr_service，不启动
[rebuild-ai-no-start]       # 只 build/create ai_service，不启动
[rebuild-funasr-no-start]   # 只 build/create funasr_runtime，不启动
[start-api]                 # 只启动 API 容器，用于后台管理、会员、支付排查
[start-huiyi]               # 启动会晓四个容器，不 build、不 recreate
```

如果同一提交同时出现两个标记，workflow 会直接失败。Gitea workflow 的 checkout 已改为脚本式 `git fetch/checkout`，避免 runner 上 `actions/checkout@v4` 缓存损坏导致部署根本没有进入 `deploy.sh`。

## ASR 安全边界

- 离线 ASR、在线 ASR、VAD、PUNC 和 runtime 内 CAMPPlus 只由 `funasr_runtime` 容器持有。
- `asr_service` 不加载旧 Dolphin ASR 模型，也不需要挂载 Dolphin 模型资产。
- 不要把 `deploy/funasr_wss_server.py` 的离线模型恢复为可随意覆盖的 `--asr_model` 参数。模型替换必须先做本地隔离实验和内存峰值验证。
- 热词、实时预处理、模型替换都不是默认优化项。它们会改变转写原文，进而影响纪要、知识库和 AI 问答，必须通过 A/B 指标后才能启用。
