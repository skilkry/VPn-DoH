# Para mantener las clases de javax.naming
-keep class javax.naming.** { *; }

# Para mantener las clases de org.apache.harmony.xnet
-keep class org.apache.harmony.xnet.provider.jsse.SSLParametersImpl { *; }

# Para mantener las clases de org.bouncycastle.jsse
-keep class org.bouncycastle.jsse.BCSSLParameters { *; }
-keep class org.bouncycastle.jsse.BCSSLSocket { *; }
-keep class org.bouncycastle.jsse.provider.BouncyCastleJsseProvider { *; }

# Para mantener las clases de conscrypt
-keep class com.android.org.conscrypt.SSLParametersImpl { *; }

-keep class javax.naming.** { *; }
-keep class javax.naming.directory.** { *; }
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn javax.naming.Binding
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider