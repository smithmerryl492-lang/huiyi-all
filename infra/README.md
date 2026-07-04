# 会晓 AI Infra

服务端部署配置目录。

当前提供本地 Docker Compose 骨架：

- `api_server`：App 统一业务接口，端口 `8080`
- `asr_service`：FunASR 文件转写与实时转写网关，端口 `8081`
- `funasr_runtime`：FunASR 2pass + CAMPPlus runtime，端口仅暴露在 Compose 网络内
- `ai_service`：LLM、embedding 与知识库问答服务，端口 `8082`

启动：

```powershell
cd F:\会晓AI\infra
docker compose up --build
```

`infra/.env` 必须提供 `HUIXIAO_DATABASE_URL`，并且该地址必须指向共享远程数据库。当前不再提供本地 PostgreSQL 容器，避免本地联调和测试服产生两套云数据。ASR 不再挂载旧 Dolphin 模型资产；FunASR/ModelScope 模型会下载到 Docker volume。LLM 和 embedding 已接入；OSS、Redis、pgvector 生产化迁移后续补。
