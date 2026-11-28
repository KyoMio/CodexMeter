# DeviceCodeOAuth

TLC reached 216 states and 672 steps. The chart draws 12 states and 26 arrows.
The chart does not draw 285 steps that stay in one chart state.

## InvCfBlockedTerminates breaks
The shortest path has 5 steps:
0. 未开始
1. 用户点登录: 申请设备码
2. 拿到设备码: 等待用户授权
3. 发起一次轮询: 轮询在飞
4. 响应送达: 轮询在飞
5. 轮询响应返回: 等待用户授权

## NoDeadlock breaks
The shortest path has 6 steps:
0. 未开始
1. 用户点登录: 申请设备码
2. 用户取消: 已取消
3. 用户点登录: 申请设备码
4. 设备码申请失败: 已失败
5. 提交凭据: 已失败
6. 校验成功: 已失败

## Rules that hold
TypeOK, InvNoDuplicateSubmit, InvSessionPreserved
