# 让 Agent 帮您出题

把下面提示词发给 WorkBuddy、Codex 或 Claude Code 等 Agent。运行 Agent 的电脑须与被控设备连接同一个局域网；普通聊天网页不能直接操作设备。

## 可复制的提示词

```text
帮我给「题了个题」加题。
设备地址：<设备连接页上的 IP:端口>
管理密码：<首次安装时设置的密码>
出题要求：<例如 20 道生活常识题，单选、多选、判断、填空混合，难度 1–3 星>

先 POST http://<地址>/api/auth
Content-Type: application/json
Body: {"passphrase":"<管理密码>"}
成功返回 {"token":"...","expiresIn":43200}。
后续所有接口都带 Authorization: Bearer <token>，不要把密码写入题库文件或日志。

先 GET /api/questions?limit=5000，避免重复；然后 POST /api/questions，批量正文为 {"items":[题目对象,...]}。
每批不超过 50 题，核对答案后导入，读取返回结果确认 added 数量。
遇到 401 重新验证密码；遇到其他错误读取 message 后修正，不要盲目重试。
```

## 题库格式

家长面板「题库 → 示例」可以下载 JSON，也可以使用 [完整示例题库](../data/banks/open-play.json)。旧版省略 `type` 的单选题仍可导入。

```json
{
  "version": 2,
  "items": [
    {"type":"single","q":"1 + 1 = ?","a":["1","2","3"],"k":1,"difficulty":1},
    {"type":"multiple","q":"选出偶数","a":["2","3","4"],"correctIndices":[0,2],"difficulty":2},
    {"type":"boolean","q":"一年有四季。","k":0,"difficulty":1},
    {"type":"text","q":"水的化学式是什么？","answers":["H2O","H₂O"],"difficulty":3,"tip":"两个氢原子与一个氧原子。"}
  ]
}
```

| 字段 | 规则 |
|---|---|
| `type` | `single` 单选、`multiple` 多选、`boolean` 判断、`text` 填空、`subjective` 主观题 |
| `q` | 题干 |
| `a` | 单选、多选的选项，2–8 个 |
| `k` | 单选答案下标，从 0 开始；判断题 0 为正确、1 为错误 |
| `correctIndices` | 多选答案下标数组，必须全部选对 |
| `answers` | 填空题的可接受答案；忽略大小写、首尾空白及全角半角差异 |
| `difficulty` | 1–5 星，默认 2 星 |
| `tip` | 可选解析 |
| `modelAnswer` | 主观题参考答案 |
| `rubric` | 主观题评分标准 |
| `id` | 可选；再次提交同一 ID 可修改题目 |

导入整批校验，任何一道题格式错误都会回滚本批，避免部分导入。可以自由编写学科、生活、安全等主题题库；奖励由家长规则设置，题库文件不能执行脚本或直接发放时长。

## API 验证

管理面板、读题库和修改规则都需要验证。`/api/auth` 换取的临时令牌有效期为 12 小时，设备进程重启或修改密码后失效。

登录有两种方式，密码都不会明文上网：

1. 挑战-响应（推荐）：先 `GET /api/auth/challenge` 拿 `{challenge, salt}`，计算
   `key = sha256(密码 + ":" + salt)`、`proof = HMAC-SHA256(key, challenge)`，
   再 `POST /api/auth` `{"challenge": "...", "proof": "..."}`；
2. 兼容旧客户端：直接 `POST /api/auth` `{"passphrase": "..."}`（明文传输，仅建议在本机使用）。

连续输错会指数退避：3 次 1 分钟、5 次 5 分钟、10 次 30 分钟；同一来源 IP 的上限更高（最多 24 小时）。

> 早期版本的 `X-Admin-Key: <管理密码>` 已移除——明文密码是不可撤销、不可过期的永久凭据。自动化脚本请改用临时令牌或只读集成令牌。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hello` | 设备基本信息（免验证） |
| GET | `/api/auth/challenge` | 取一次性随机数，用于挑战-响应登录 |
| POST | `/api/auth` | 用管理密码（或挑战应答）换取令牌 |
| GET | `/api/state` | 规则、当天统计、已解锁时长和加练池 |
| PUT | `/api/config` | 保存规则 |
| GET | `/api/questions?limit=5000` | 读取题库 |
| POST | `/api/questions` | 新增或修改单题，或批量导入 `items` |
| DELETE | `/api/questions/{id}` | 删除题目 |
| GET | `/api/history?days=7` | 实际使用时长与答题统计 |
| GET | `/api/events?limit=50` | 近期记录 |

`/api/quiz/start` 与 `/api/quiz/answer` 仅供被控设备内部使用，管理 API 令牌不能代答或领取奖励。

## 公式与主观题

题干、选项、参考答案中可使用 `$...$` 行内公式或 `$$...$$` 独立公式。JSON 中的反斜杠需转义，例如 `"q":"计算 $\\\\frac{1}{2}+\\\\frac{1}{4}$。"`。KaTeX 和字体内置到 APK，学习端离线可显示。

主观题示例：

```json
{"type":"subjective","q":"解方程 $x^2-5x+6=0$，写出过程。","modelAnswer":"$(x-2)(x-3)=0$，所以 $x=2$ 或 $x=3$。","rubric":"列式 50 分，结论 50 分。","difficulty":3}
```

## 用 Agent 批改

家长面板「批改」选择「家长或 Agent 批改」。Agent 与设备在同一局域网，先用管理密码取得令牌，再执行：

1. `GET /api/reviews?state=pending` 获取待批作答。每条包含 `id`、`question`（题干、参考答案、评分标准）、`input.text` 及可选 `input.drawing`（PNG data URL）。
2. 根据参考答案和评分标准评分。手写图片需 Agent 具备图像理解能力，无法判断时留给家长。
3. `POST /api/reviews/<id>`，正文为 `{"score":85,"feedback":"思路正确，请注意最后一步的单位。"}`。
4. 同一条作答重复提交评分不会重复结算。按家长设置的及格分判定是否答对，随后按任务规则发放时长。

全部请求携带 `Authorization: Bearer <token>`。Agent 不能自行编造完成事件或直接给孩子增加时间。

## 配置 AI 自动批改

在面板「批改」中选择 AI 自动批改，填写 HTTPS 的完整 chat/completions 地址、模型和 API Key。DeepSeek 可填 `https://api.deepseek.com/chat/completions` 与 `deepseek-chat`。密钥只保存在被控设备，不会在读取配置时返回。

开启后，题目、评分标准和孩子的答案会发送到家长配置的服务。DeepSeek 等支持图片的模型可同时批改文字和手写图片，需在面板开启「模型支持图像」；不支持图片的模型只批文字答案。调用失败或模型不能处理图像时，答案保留在待批队列，可人工评分或重试。

- `GET /api/grading`：读取批改设置，密钥只返回是否已配置。
- `PUT /api/grading`：保存 `mode`（`manual` / `ai`）、`endpoint`、`model`、`apiKey`、`vision`、`passScore`。省略或留空密钥表示保持原值。
- `GET /api/reviews?state=all`：查询近期作答及批改结果。
- `POST /api/reviews/<id>/retry`：按当前 AI 设置重试。

## 玩法规则

详见 [玩法设置](./gameplay.md)。设备在局域网内提供 HTTP 服务，请仅在可信网络中连接。
