# gRPC (via canton-dapp-lan) references JNDI / java.beans classes that do not
# exist on Android and are never reached at runtime. Suppress under R8 (the app
# builds with android.enableR8.fullMode=false, which surfaces these).
-dontwarn javax.naming.**
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
