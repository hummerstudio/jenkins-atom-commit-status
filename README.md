# jenkins-atom-github

Commit status -- port of **github / gitlab-plugin** (198,332) as an atom, plus the behaviour of
**pipeline-githubnotify-step** (4,258, the `githubNotify` step) for GitHub.

One atom for GitHub, GitLab and Gitea commit statuses: the three APIs differ only in URL and body shape.
`UNSTABLE` maps to `failure` on GitHub and `failed` on GitLab.

## 两个动作 (1.2.0)

一个产品, 两个报告接口. GitHub 的提交页上能写两种东西: **commit status** (statuses API, 一行小字加一个链接) 和
**check run** (checks API, 能带行级注解, 能分批次写入). 所以 1.2.0 把它们合成一个原子, 用 `action` 选:

| action | 接口 | 平台 | 参数组 |
|---|---|---|---|
| `status` (默认, 1.1.0 的行为) | `POST /repos/{o}/{r}/statuses/{sha}` | GitHub / GitLab / Gitea | `provider` `state` `contextName` `description` `targetUrl` `verifyStatus` `prNumber` `prComment` |
| `checks` (从 `github-checks` 原子并入) | `POST/PATCH /repos/{o}/{r}/check-runs` | 只有 GitHub | `name` `status` `conclusion` `title` `summary` `summaryFile` `text` `textFile` `annotationsFile` `detailsUrl` `externalId` `reuseExisting` `verify` `maxMessageSize` |

两组共用的只有身份参数: `account` / `repo` / `sha` / `apiUrl` / `token` / `preflight`. 别的动作不认识的值不猜:
`action` 写错就是 USER `40001`, 消息里写明 `status / checks` 两个值. `status` 那一路的代码和测试一个字节没动.

## 动作 checks: GitHub check run

`github-checks` 原子 (移植自同名插件, 3,534 installs; 报文规则在同系列的 `checks-api` 里) 原样搬进来:

| 插件 / checks-api 的行为 | 这个动作 |
|---|---|
| `ChecksStatus`: NONE/QUEUED -> `queued`, IN_PROGRESS -> `in_progress`, COMPLETED -> `completed` | 同 |
| `BuildStatusChecksPublisher.getConclusion`: SUCCESS -> success, UNSTABLE -> neutral, FAILURE -> failure, NOT_BUILT -> skipped, ABORTED -> canceled | 同, 但 `ABORTED` 发 `failure` (`GitHubChecksDetails` 把 CANCELED 映成 FAILURE, GitHub 只让自己的 Actions 写 `cancelled`); 要别的就显式填 `conclusion` |
| `truncateIfNeeded`: 超过 `maxMessageSize` (默认 65535) 时留头加 `... [Output truncated; original size was N characters] ...`, 不切断代理对 | 同 |
| 回 `Only N characters are allowed; M were supplied` 时 `calculateReducedLimit` 重算上限再发一次 (`max(100, allowed - overshoot - 100)`) | 同, 只重试一次, 日志写明新上限 |
| 每请求最多 50 条注解, 超出要多次 update, 每次 update **追加** | 同: 首请求 50 条, 其余按 50 一批 PATCH, 顺序保持 |
| check id 存在 build action 里, 同一构建重发就是 update | `external_id` + `GET /commits/{sha}/check-runs?check_name=` 找回同一个 check; `external_id` 不同就是另一个构建 |
| `details_url` / `external_id` 缺省用构建 URL / `Run.getExternalizableId()` | 缺省 `BUILD_URL` / `BUILD_TAG` |

checks 动作的错误码 (与 status 动作的编号各自独立, 消息原文照搬 `github-checks`):
`40001` 参数/组合非法, `40002` 凭据无效或缺失, `40003` 仓库不存在, `40004` 提交不在仓库里, `40005` 注解文件不可用,
`40006` 接口拒绝写入, `40007` 回读与所发不一致, `40009` 回包不是 check run / 读不回来, `40010` 前置查询失败.

## What `pipeline-githubnotify-step` actually is (1.1.0)

The plugin is 19 KB and has nothing to do with pull-request comments: `GitHubStatusNotificationStep$Execution.run`
does exactly one write, `GHCommit.createCommitStatus(...)`, and its descriptor calls itself
"Notifies GitHub of the status of a Pull Request". `javap` over `pipeline-githubnotify-step.jar`
(`49.vf37bf92d2bc8`, 4 classes) finds no comment code at all -- the word "comment" does not appear in the jar.

What the step does around that write is the part worth porting:

| 插件行为 (githubNotify) | 原子行为 |
|---|---|
| `account` (owner) + `repo` (仓库名) 两个参数 | `account` + `repo`; `repo` 仍可写成 `owner/name`, 只是先拆开 |
| `getRepoIfValid`: 先 GET 仓库, 不存在就 `The specified repository does not exist for the specified account` | `preflight=true` (默认) 时 `GET /repos/{account}/{repo}`, 404 -> USER `40007`, 退出前不发任何写请求 |
| `getGitHubIfValid`: `isCredentialValid()`, 无效就 `The supplied credentials are invalid to login` | `GET /user`, 401 -> USER `40006` |
| `GHRepository.getCommit(sha)`: 提交不在仓库就 `The specified commit does not exist in the specified repository` | `GET /repos/{account}/{repo}/commits/{sha}`, 404/422 -> USER `40008` |
| 状态写在 `GHCommit.getSHA1()` 上, 即**接口返回的 sha**, 不是调用方给的短 sha | 同: 短 sha 先解析成接口给的完整 sha, 再 `POST .../statuses/{完整 sha}`, 输出 `sha` |
| 缺参时从 `GitHubSCMSource` / `SCMRevisionAction` 推断 (多分支, PR 取 pull hash) | 推断不了 master/job 模型; 只做 `GIT_URL` 推断 `owner/name`, `GIT_COMMIT` 推断 sha, 其余报 USER 错误 |
| `GHCommitState` 四值: `error` / `failure` / `pending` / `success` | 映射一致 (`ABORTED` -> `error`, `UNSTABLE`/`FAILURE` -> `failure`) |
| 默认 context `jenkins/githubnotify` | 默认仍是 `jenkins/atom`, 不劫持老用户的 context; 要一致就显式填 |
| 状态码 201 就算成功 | 还要求回包里 `state` / `context` 与所发一致 (`verifyStatus`, 默认开), 否则 `40009` |

## 与插件不同的地方 (有意为之)

1. **不信状态码**. GitHub 创建状态时回的是刚创建的那个对象, 所以原子读它: `state` 必须是映射后的值, `context`
   必须是我们发的那一个. 一个反向代理可以回 201, 回不了 "我刚存下的 state".
2. **错误分类**. 插件把这些情况都抛 `IllegalArgumentException`; 原子分 USER (配置错, 改参数能解决) 与
   THIRD_PART (对端或网络), 失败信息保留插件原话的关键词, 日志里好搜.
3. **凭据是 secret**. 插件用 Jenkins 用户名密码凭据; 原子收 `token` (Secret text), 只出现在请求头里, 不进日志.

## PR 评论: 插件没有, 这是扩展

`githubNotify` 不写评论. 要发评论的人还是在 Jenkinsfile 里手写 `httpRequest`, 所以原子带了一个 `prComment`:
`POST /repos/{repo}/issues/{n}/comments {"body": ...}`, 支持 `${VAR}` 展开 (构建参数 / 环境变量), PR 编号取
`prNumber`, 留空取 `CHANGE_ID`.

写完**回读**: `GET /repos/{repo}/issues/{n}/comments?per_page=100` (翻页, 最多 10 页), 要求列表里真有一条正文
**逐字相同**的评论, 取其 `id` 当输出. GitHub 回 201 / 代理回 200 / 正文被截断被转义吞掉, 都只有回读能发现,
`40010` 就是为这个准备的. GitLab 的对应接口是 notes 不是 issue comments, 给 `prComment` 会直接 USER `40011`.

## 输入 (action=status)

| 参数 | 说明 |
|---|---|
| `provider` | `github` (默认) / `gitlab` / `gitea` |
| `account` + `repo` | GitHub/Gitea 的 owner 与仓库名; `repo` 写成 `owner/name` 也行; 都留空时用 `GIT_URL` 推断 |
| `sha` | 提交 sha, 留空取 `GIT_COMMIT`; 开了 `preflight` 会被接口解析成完整 sha |
| `state` | 留空按 `BUILD_STATUS` 推断 |
| `contextName` / `description` / `targetUrl` | 留空按 `jenkins/atom` / `build #N <state>` / `BUILD_URL` 生成 |
| `apiUrl` | API 根地址; `gitea` 必填 |
| `token` | 凭据 (Secret text). GitHub/Gitea 走 `Authorization: Bearer`, GitLab 走 `PRIVATE-TOKEN` |
| `preflight` | 默认 `true`: 先核对凭据, 仓库, 提交 (GitHub/Gitea) |
| `verifyStatus` | 默认 `true`: 核对回包里的 `state` / `context` |
| `prNumber` / `prComment` | PR 评论; 正文留空就不发 |

输出 (两个动作共用一个 `output`, 各自写自己那几个): `status` (HTTP 码), `state`, `sha` (实际写状态的 sha),
`verified` (回读到的状态 / 结论), `apiUrl`, `commentId` (回读到的评论 id); checks 另有 `id`, `name`, `headSha`,
`conclusion`, `annotations` (发出的条数), `checkUrl`, `annotationsVerified` (回读到的条数).

## 构建与验证

```bash
mvn verify                  # 打包 + 61 个集成测试 (4 个类, 见下)
python3 tools/mutations.py  # 4 个故意植入的 bug, 每个都必须被对应测试抓住
```

| 测试类 | 覆盖 |
|---|---|
| `CommitStatusIT` (18) | action=status: 预检, sha 解析, 三种平台的报文, PR 评论回读, 拒绝路径 |
| `ActionDispatchIT` (4) | `action` 本身: 缺省是 status, `action=status` / `checks` 各自走到哪个接口, 未知动作报 `status / checks` 两个值 |
| `GithubChecksIT` / `GithubChecksContractIT` / `GithubChecksExtraIT` (39) | action=checks: 报文与回读契约 (`tools/fake-check-api.py`), 每个接口失败的分支, 注解的本地校验 |

`tools/fake-check-api.py` 是 checks 的假 API: 按 REST 文档校验报文 (必需字段, conclusion 与 status 的搭配, 每条注解的
行/列/级别, **每请求最多 50 条并在每次 update 时追加**), 超长时回 GitHub 的原话; `tools/check.py` 再从原子**自己的输入**
独立重算一遍 (仓库推断, 结论映射, `${VAR}` 展开, 截断公式, 注解默认值), 与服务端状态、请求日志三方比对.

`tools/fake-github.py` 是那个假 API: 它按 GitHub 的语义回应 (`/user` 认 token, 仓库/提交 404, 状态回显,
评论列表), 两种模式让失败路径是真的 -- `--lose-tail` 只存评论前 10 个字符 (接口照样回 201), 账号 `liar`
回显一个不是我们发的 state. 测试断言的是**它记下的请求** (`received.tsv`): 路径 (含解析后的完整 sha),
`state`/`context` 字段, `Bearer` 头, 评论正文, 以及 token 从不进日志.
