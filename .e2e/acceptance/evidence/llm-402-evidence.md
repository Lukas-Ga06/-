# 真实 LLM 调用证据：DeepSeek Key 余额为 0（402）

实例：http://127.0.0.1:18091（LLM_ENABLED=true，Key 来自 travel-agent-server/.env.local）
时间：2026-11-19 14:17-14:18

```
2026-11-19T14:17:59.179+08:00  WARN [adb07d61333d4d1f] 27196 --- [travel-agent-server] [io-18091-exec-5] c.t.agent.llm.OpenAiCompatibleLlmClient  : LLM ����ʧ�ܣ�����Ϊ����ģ��: 402 Payment Required: "{"error":{"message":"Insufficient Balance (request_id: 83b5d78b-bfa6-41a8-9854-0c1131989351)","type":"insufficient_balance_error","param":null,"code":"invalid_request_error"}}"
2026-11-19T14:17:59.682+08:00  WARN [adb07d61333d4d1f] 27196 --- [travel-agent-server] [io-18091-exec-5] c.t.agent.llm.OpenAiCompatibleLlmClient  : LLM ����ʧ�ܣ�����Ϊ����ģ��: 402 Payment Required: "{"error":{"message":"Insufficient Balance (request_id: de6ef85c-a078-413e-afca-726c8a10d440)","type":"insufficient_balance_error","param":null,"code":"invalid_request_error"}}"
2026-11-19T14:18:00.060+08:00  WARN [adb07d61333d4d1f] 27196 --- [travel-agent-server] [io-18091-exec-5] c.t.agent.llm.OpenAiCompatibleLlmClient  : LLM ����ʧ�ܣ�����Ϊ����ģ��: 402 Payment Required: "{"error":{"message":"Insufficient Balance (request_id: 296fd402-fcb3-4e39-a226-8506fb9f21b2)","type":"insufficient_balance_error","param":null,"code":"invalid_request_error"}}"
```
