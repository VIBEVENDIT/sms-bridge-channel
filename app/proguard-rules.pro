# OkHttp ships consumer rules. Keep only the app entry points referenced from the manifest.
-keep class com.hotelatgangnam.smsbridge.MainActivity { *; }
-keep class com.hotelatgangnam.smsbridge.BridgeService { *; }
-keep class com.hotelatgangnam.smsbridge.SmsReceiver { *; }
-keep class com.hotelatgangnam.smsbridge.BootReceiver { *; }
