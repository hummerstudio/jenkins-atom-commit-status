# jenkins-atom-commit-status

Commit status -- port of **github / gitlab-plugin** (198,332) as an atom, plus the behaviour of
**pipeline-githubnotify-step** (4,258, the `githubNotify` step) for GitHub.

One atom for GitHub, GitLab and Gitea commit statuses: the three APIs differ only in URL and body shape.
`UNSTABLE` maps to `failure` on GitHub and `failed` on GitLab.

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

## 输入

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

输出: `status` (HTTP 码), `state`, `sha` (实际写状态的 sha), `verified` (回读到的状态), `apiUrl`,
`commentId` (回读到的评论 id).

## 构建与验证

```bash
./build.sh
./tools/smoke.sh            # 15 个场景, 假 API 独立记录并校验每个请求
python3 tools/mutations.py  # 4 个故意植入的 bug, 每个都必须被对应断言抓住
```

`tools/fake-github.py` 是那个假 API: 它按 GitHub 的语义回应 (`/user` 认 token, 仓库/提交 404, 状态回显,
评论列表), 两种模式让失败路径是真的 -- `--lose-tail` 只存评论前 10 个字符 (接口照样回 201), 账号 `liar`
回显一个不是我们发的 state. smoke 断言的是**它记下的请求**: 路径 (含解析后的完整 sha), `state`/`context`
字段, `Bearer` 头, 评论正文, 以及 token 从不进日志.
