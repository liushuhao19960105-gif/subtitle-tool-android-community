# 安全政策 / Security Policy

## 私密报告
如果发现 API Key、签名密钥、敏感数据泄露，或可导致用户数据暴露的漏洞，请**不要**创建公开 Issue，也不要附上私密日志或测试素材。

优先使用仓库的 **Security → Advisories → Report a vulnerability**（前提是维护者已启用 private vulnerability reporting）。若此功能未启用，请先通过仓库管理员认可的私人渠道联系维护者，不要公开漏洞细节。

维护者应先验证报告，再制定修复、测试与披露计划。当前没有承诺固定响应时限。

## 支持范围
最新 main 分支及最新正式构建为优先修复对象；历史 APK 可能不再得到安全更新。

## 签名和云服务
- 私钥/密码仅在维护者受控的环境及受保护的 GitHub Secrets/Environment 中使用。
- PR 中不得读取 Secrets，构建日志不得回显 API Key、用户字幕或签名数据。
- 用户输入的自定义 API 地址会收到相关请求内容，请核实其可信度。
