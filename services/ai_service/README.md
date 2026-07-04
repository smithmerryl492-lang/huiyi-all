# 会晓 AI AI Service

LLM 纪要、待办、风险、问题和知识库问答服务位置。

当前通过阿里云百炼 OpenAI 兼容接口接入 LLM 与 embedding 模型。运行密钥只放本机或部署环境变量，不写入仓库。

- 会议摘要生成
- 决策、风险、问题提取
- 待办事项提取
- 跨会议知识库问答
- Prompt 模板管理
- 结果质量与来源引用约束

运行：

```powershell
cd F:\会晓AI\services\ai_service
$env:HUIXIAO_LLM_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode/v1'
$env:HUIXIAO_LLM_API_KEY='replace-with-local-key'
$env:HUIXIAO_LLM_MODEL='qwen3.7-plus'
$env:HUIXIAO_EMBEDDING_MODEL='text-embedding-v4'
$env:HUIXIAO_EMBEDDING_DIMENSIONS='1024'
python -m uvicorn app.main:app --host 127.0.0.1 --port 8082
```

接口：

```text
GET  /health
POST /api/v1/minutes
POST /api/v1/embeddings
POST /api/v1/knowledge/answer
```
