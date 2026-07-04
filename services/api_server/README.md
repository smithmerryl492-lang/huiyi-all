# 会晓 AI API Server

App 统一后端入口。当前阶段已接入独立 ASR 服务和独立 AI 服务：

- 健康检查
- 音频/视频文件上传
- 本地临时文件保存
- 共享远程数据库中的任务与结果存储
- 处理任务创建、查询、重试
- 调用 `asr_service` 生成转写结果
- 调用 `ai_service` 生成纪要、决策、待办
- 写入知识库索引
- 跨会议知识库问答
- 会议详情结果读取
- 本地服务端数据清理

运行：

```powershell
cd F:\会晓AI\services\api_server
$env:HUIXIAO_DATABASE_URL='mysql+pymysql://user:password@remote-host:3306/database?charset=utf8mb4'
$env:HUIXIAO_ASR_SERVICE_URL='http://127.0.0.1:8081'
$env:HUIXIAO_AI_SERVICE_URL='http://127.0.0.1:8082'
python -m uvicorn app.main:app --host 127.0.0.1 --port 8080
```

接口文档：

```text
http://127.0.0.1:8080/docs
```

上传文件默认写入：

```text
F:\会晓AI\services\api_server\data
```

任务、文件元数据和处理结果必须写入共享远程数据库。`HUIXIAO_DATABASE_URL` 未设置时 API 会启动失败，避免误写本机数据库。`infra/docker-compose.yml` 只启动业务服务，不再提供本地 PostgreSQL。

当前任务处理会先调用 `asr_service` 生成转写结果，再调用 `ai_service` 生成纪要、决策和待办，并把转写片段、摘要、决策、待办写入知识库索引。

知识库问答：

```text
POST /api/v1/knowledge/ask
```
