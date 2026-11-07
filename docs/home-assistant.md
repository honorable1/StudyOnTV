# 与 Home Assistant 联动

「题了个题」管的是**内容层**：想看电视，先把题做了。
Home Assistant（下称 HA）管的是**设备层**：电视在哪个信号源、音量多大、能开多久，都不经过 App。

两者不冲突，但**不能各算一套时间**，否则孩子会被扣两次。本文说明怎么分工，以及怎么把本项目的状态接进 HA 做展示。

> 本文只用到已有的局域网 HTTP 接口，不需要改动 App 逻辑。

## 一、先想清楚需不需要 HA

| 你家的观看方式 | 建议 |
|---|---|
| 全部在电视内的 App（云视听、B 站、爱奇艺……） | **只用本项目就够**，不需要 HA |
| 有 HDMI 机顶盒 / 直播 / 游戏机 | 需要 HA，因为答题门禁只能拦前台 App，拦不住信号源 |
| 想要音量上限、到点关机、睡眠模式 | 需要 HA，本项目不做设备层管控 |

原因很直接：本项目判断的是「当前是不是被管控的前台」，HDMI 输入不是 App，门禁覆盖不到这条通道。设备层的锁在电视外面，孩子拿遥控器绕不过去。

```
┌────────────────────┐        HTTP 8099         ┌──────────────────┐
│  电视 / 平板        │ ◄──────────────────────► │ Home Assistant   │
│  题了个题 App       │   /api/state（只读）      │ 统计 / 面板 / 周报 │
│  答题门禁 + 配额     │                          └────────┬─────────┘
└────────────────────┘                                   │ ADB / 集成
                                                          ▼
                                                 信号源锁 / 音量锁 / 定时关机
```

## 二、把状态接进 HA（只读）

设备在局域网内提供 HTTP 服务，默认端口 `8099`，`/api/state` 需要验证。

推荐在家长面板里生成一个**只读集成令牌**（家长管理 → 集成 → 生成只读令牌）。令牌只在生成时显示一次，之后只保存哈希；权限可以随时在面板里收紧或吊销。

把令牌放进 `secrets.yaml`，不要写进会被提交的 YAML 或截图里：

```yaml
# secrets.yaml
studyontv_auth: "Bearer sotv_你的令牌"
```

> 早期版本支持用 `X-Admin-Key` 直接带管理密码，已经移除：明文密码是不可撤销、不可过期的永久凭据，一旦泄露只能改密码。

然后加一个 REST 传感器：

```yaml
# configuration.yaml
sensor:
  - platform: rest
    name: StudyOnTV Remaining
    unique_id: studyontv_remaining
    resource: http://<电视IP>:8099/api/state
    headers:
      Authorization: !secret studyontv_auth
    value_template: "{{ (value_json.learning.remainingSeconds / 60) | round(0) }}"
    unit_of_measurement: min
    scan_interval: 120
    json_attributes:
      - learning
      - today
```

用起来是这样：

- `sensor.studyontv_remaining`：还能看几分钟（本次答题解锁的剩余时长）；
- `attributes.learning.reason`：为什么锁着，取值 `quota`（当天配额用完）、`rounds`（答题组数用完）、`schedule`（不在可用时段）、`timeup`（剩余时长用完），空字符串表示可以看；
- `attributes.learning.nextAvailableAt`：下次可用的时间戳（毫秒，`0` 表示没有）；
- `attributes.today.usedMinutes` / `answered` / `correct` / `accuracy`：今天的观看与答题统计。

翻译成一句人话的模板传感器：

```yaml
template:
  - sensor:
      - name: StudyOnTV Status
        unique_id: studyontv_status
        state: >-
          {% set reason = state_attr('sensor.studyontv_remaining', 'learning').reason
                           if state_attr('sensor.studyontv_remaining', 'learning') else '' %}
          {{ {'quota': '今日配额已用完',
              'rounds': '今日答题组数已用完',
              'schedule': '不在可用时段',
              'timeup': '可看时长已用完'}.get(reason, '可以看') }}
```

> `scan_interval` 别设太短。电视是低配设备，服务端也在同一台机器上跑答题，`60`～`300` 秒足够展示用；要秒级联动请走下面的设备层，不要高频轮询网页服务。

如果 `/api/state` 返回 401，传感器会变成 `unavailable`——通常是密码改了，或者密码里带了会被 YAML 吃掉的特殊字符（用引号包起来）。

## 三、分工：别让两套规则同时扣时间

建议按**时间段**划分，同一时刻只有一套规则在生效：

| 时段 | 谁管 | HA 侧动作 |
|---|---|---|
| 17:00–21:00 作业时段 | 本项目 | HA 的配额/定时锁自动化加条件跳过 |
| 其余时间（白天老人看、深夜） | HA | 信号源锁 / 音量上限 / 22:30 关机 |

用一个开关标记当前归谁管：

```yaml
input_boolean:
  studyontv_window:
```

```yaml
automation:
  - alias: "[电视] 作业时段交给题了个题"
    trigger:
      - platform: time
        at: "17:00:00"
    action:
      - service: input_boolean.turn_on
        target: { entity_id: input_boolean.studyontv_window }

  - alias: "[电视] 作业时段结束，收回设备层"
    trigger:
      - platform: time
        at: "21:00:00"
    action:
      - service: input_boolean.turn_off
        target: { entity_id: input_boolean.studyontv_window }
```

然后给 HA 自己的「每日配额 / 定时锁」自动化加一条条件：

```yaml
condition:
  - condition: state
    entity_id: input_boolean.studyontv_window
    state: "off"
```

这样 21 点之后由本项目锁着的那台电视，不会被 HA 再算一遍时长；反过来 HA 锁信号源时，也不会去动 App 里的余额。

## 四、HDMI 通道交给设备层

如果孩子会切到机顶盒，光锁 App 是不够的——**只锁前台 App = 假锁**，切个信号源就绕过去了。设备层的最小组合：

1. **信号源锁**：锁定目标 HDMI，被切走就切回。不要相信电视上报的 `source`（云通道有缓存、还会停更），用 ADB 每 10 秒读一次真实前台；
2. **锁 App**：锁定期 `pm disable-user` 掉娱乐包，解锁 `pm enable` 恢复，全程可逆、不需要 root；
3. **音量上限 + 到点关机**：避免「锁了内容但声音还在」这类漏洞。

完整的 23 条自动化、辅助元素和踩坑记录在另一套教程里，可直接套用：
<https://github.com/cbywyz/ha-tv-kids-lock>

## 五、几个容易踩的坑

- **一台电视只有一个 ADB 会话**。HA 的 Android TV 集成、`adb shell`、投屏工具同时连会互相踢，表现为「自动化跑着跑着就不动了」；
- **管理密码 = 家长验证密码**。接进 HA 后它会被写进 `secrets.yaml`、出现在日志或截图里，建议定期更换，并且不要把 HA 配置公开到公网；
- **HTTP 是明文的**。只在可信局域网内用，别做端口映射；
- **两套计时打架**比不装更难排查。先确认「这个时段谁在管」，再看为什么没锁；
- **App 被杀掉后 8099 不再响应**，HA 侧传感器会失联。要不要做「失联就断网/关机」的兜底，取决于你对误伤的容忍度，建议先只做通知。

## 六、可以怎么继续演进

如果以后愿意加一档「被外部家庭自动化调用」的能力，下面两个入口会让联动干净很多（目前**都还没有实现**，仅作为讨论）：

1. `/api/state` 提供一个只读、免管理密码的本机开关，只暴露 `remainingSeconds`、`usedMinutes`、`reason` 这类统计字段，方便 HA 做展示而不必存密码；
2. 一个明确的「家长侧解锁 N 分钟」入口（HTTP 或 webhook 均可），让两套系统可以互补，而不是各算一套时间。

在此之前，本文的只读接法已经够用。
