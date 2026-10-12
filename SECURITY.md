# 安全说明

本项目面向已 root 的 RK3399 局域网开发环境。远程控制继续使用 HTTP Basic 与默认 admin/admin，可通过现有接口修改控制密码；不将 8765 端口映射到公网。

App 的 rk3399_control.xml 同时保存远程控制、Wi-Fi、PPPoE 密码及其他设置，已通过旧版 backup_rules 与 Android 12+ data_extraction_rules 排除出云备份和设备迁移。这不会清除现有数据，也不会改变普通覆盖安装的配置；清除数据或迁移设备后需重新配置。本次没有改变密码存储方式、HTTP 协议或认证流程。

root 脚本会修改网络及服务配置。存储格式化会清空选定 USB 卷；USB 启动预设会写入当前固件的启动分区。只针对本项目当前板子使用，事先保留数据备份和固件恢复方式。

不要提交签名密钥、设备镜像、访问令牌、真实密码或设备备份。报告安全问题时优先使用 GitHub Security 私密报告入口；不要在公开 Issue 中附带凭据。

Android 备份规则参考：[官方文档](https://developer.android.com/identity/data/autobackup)。本次没有进行云端备份／恢复或设备迁移实测。
