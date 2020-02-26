# graal-cassandra-driver
A simple example of using the DSE Cassandra driver in a Graal native image

# Introduction
This repositoriy aims to provide a minimal example of using the modern version of the [DataStax Java driver](https://github.com/datastax/java-driver) in a native image generated by the Graal tool chain.  These examples explicitly target the driver only; adding additional libraries and dependencies will by definition complicate Graal's image generation process and obscure the functionality being illustrated here.

As this effort went along it became clear that the drivers interaction with native code represented a unique challenge.  This interaction (and it's functionality when running in a native image) is explored in more depth below.

# Running and Building
The keyspace counter application can be run directly as a Java application using Gradle:

```./gradlew run```

An argument of "debug" will enable log4j output to the console:

```./gradlew debug```

The native image is generated using the [gradle-graal plugin](https://github.com/palantir/gradle-graal):

```./gradlew nativeImage```

Once generation completes the native image can be run directly:

```build/graal/keyspaceCounter```

# Some Notes on Interactions with Native Code
The Java driver attempts to use various [jnr](https://github.com/jnr) libs for a few native operations.  These include:

* Retrieving system time with microsecond granularity via libc through [jnr-ffi](https://github.com/jnr/jnr-ffi) and [jffi](https://github.com/jnr/jffi)
* Retrieving PID via [jnr-posix](https://github.com/jnr/jnr-posix)
* Retrieving CPU information via the [Platform class](https://github.com/jnr/jnr-ffi/blob/jnr-ffi-2.1.10/src/main/java/jnr/ffi/Platform.java) in jnr-ffi

The [Native class](https://github.com/datastax/java-driver/blob/4.4.0/core/src/main/java/com/datastax/oss/driver/internal/core/os/Native.java) provides a reasonable starting point for all of this functionality

## Dynamic Proxies in jnr-ffi
Normally jnr-ffi will generate a proxy for the native library interface (Native.LibCLoader.LibC in our case) via asm.  In this case the methods of the interface result in calls to the underlying native functionality via jffi.  If this process fails an "error proxy" is returned.  This proxy is a conventional java.lang.reflect.Proxy instance which throws a specified exception on every method call.  This error proxy is defined [here](https://github.com/jnr/jnr-ffi/blob/jnr-ffi-2.1.10/src/main/java/jnr/ffi/LibraryLoader.java#L340-L349) and used in catch blocks of the load() method of the same class.

For Graal to do it's work all classes must be available at build-time.  Note that proxies like this error proxy [are supported](https://github.com/oracle/graal/blob/master/substratevm/LIMITATIONS.md#dynamic-proxy) but must be declared in the proxy config and eval'd at build-time.

Note that these proxies are only required if we have a problem loading the underlying native code at run-time, but since there's no way to control for this at build-time they must be included here.

## Dynamic Classes in jnr-ffi
Once we've accounted for the dynamic proxies above we immediately run into another problem.

As mentioend above jnr-ffi will attempt to dynamically create a class implementing the library interface via asm.  The output of this process is then used to generate a dynamic class via ClassLoader.defineClass() [here](https://github.com/jnr/jnr-ffi/blob/jnr-ffi-2.1.10/src/main/java/jnr/ffi/provider/jffi/AsmLibraryLoader.java#L235).  This results in the following error message in the log4j output:

> 31 [s0-admin-0] DEBUG com.datastax.oss.driver.internal.core.os.Native  - Error loading libc
> java.lang.RuntimeException: com.oracle.svm.core.jdk.UnsupportedFeatureError: Unsupported method java.lang.ClassLoader.defineClass(String, byte[], int, int) is reachable: The declaring class of this element has been substituted, but this element is not present in the substitution class
>        at jnr.ffi.provider.jffi.AsmLibraryLoader.generateInterfaceImpl(AsmLibraryLoader.java:247)
>        at jnr.ffi.provider.jffi.AsmLibraryLoader.loadLibrary(AsmLibraryLoader.java:89)
>        at jnr.ffi.provider.jffi.NativeLibraryLoader.loadLibrary(NativeLibraryLoader.java:44)
>        at jnr.ffi.LibraryLoader.load(LibraryLoader.java:325)
>        at jnr.ffi.LibraryLoader.load(LibraryLoader.java:304)

This is consistent with the [stated limitation on dynamic class loading](https://github.com/oracle/graal/blob/master/substratevm/LIMITATIONS.md#dynamic-class-loading--unloading) in the Graal docs.  There's no obvious way around this problem; it looks like asm-generated proxies are simply not usable in a Graal context.

## jnr-ffi Platform Support
As mentioned above the driver attempts to retrieve CPU information via jnr.ffi.Platform.  This class is loaded via reflection from within com.datastax.oss.driver.internal.core.os.Native so including it within the reflection config appears to be adequate.

Note that we also have to add platform-specific type support as well; thus the inclusion of jnr.ffi.Platform$Linux in addition to jnr.ffi.Platform.  This has the result of making the generated image platform-specific.

## jnr-posix POSIX Support
