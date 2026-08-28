package com.example.rk3399_control

import android.os.IBinder
import java.net.InetAddress

/** Runs through app_process as root to call this Android 7 ROM's hidden Ethernet service. */
object EthernetRootCli {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "ethernet") as IBinder
            val stub = Class.forName("android.net.IEthernetManager\$Stub")
            val ethernet = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

            if (args.firstOrNull() == "inspect") {
                ethernet.javaClass.methods
                    .filter { it.name.contains("config", true) || it.name.contains("ethernet", true) }
                    .forEach { println(it.toGenericString()) }
                return
            }

            val ipConfigurationClass = Class.forName("android.net.IpConfiguration")
            val ipAssignmentClass = Class.forName("android.net.IpConfiguration\$IpAssignment")
            val proxySettingsClass = Class.forName("android.net.IpConfiguration\$ProxySettings")
            val configuration = ipConfigurationClass.getConstructor().newInstance()

            fun enumValue(clazz: Class<*>, name: String): Any = clazz.enumConstants.first { (it as Enum<*>).name == name }
            ipConfigurationClass.getField("proxySettings").set(configuration, enumValue(proxySettingsClass, "NONE"))

            when (args.firstOrNull()) {
                "dhcp" -> {
                    ipConfigurationClass.getField("ipAssignment").set(configuration, enumValue(ipAssignmentClass, "DHCP"))
                    ipConfigurationClass.getField("staticIpConfiguration").set(configuration, null)
                }
                "static" -> {
                    require(args.size >= 4) { "static IP PREFIX GATEWAY [DNS1] [DNS2]" }
                    val staticClass = Class.forName("android.net.StaticIpConfiguration")
                    val staticConfig = staticClass.getConstructor().newInstance()
                    val ip = InetAddress.getByName(args[1])
                    val prefix = args[2].toInt()
                    val linkAddressClass = Class.forName("android.net.LinkAddress")
                    val linkAddress = linkAddressClass.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
                        .newInstance(ip, prefix)
                    staticClass.getField("ipAddress").set(staticConfig, linkAddress)
                    if (args[3].isNotBlank() && args[3] != "-") {
                        staticClass.getField("gateway").set(staticConfig, InetAddress.getByName(args[3]))
                    }
                    @Suppress("UNCHECKED_CAST")
                    val dns = staticClass.getField("dnsServers").get(staticConfig) as MutableList<InetAddress>
                    args.drop(4).filter { it.isNotBlank() && it != "-" }.forEach { dns += InetAddress.getByName(it) }
                    ipConfigurationClass.getField("ipAssignment").set(configuration, enumValue(ipAssignmentClass, "STATIC"))
                    ipConfigurationClass.getField("staticIpConfiguration").set(configuration, staticConfig)
                }
                else -> error("usage: EthernetRootCli {inspect|dhcp|static IP PREFIX GATEWAY [DNS1] [DNS2]}")
            }

            val method = ethernet.javaClass.methods.firstOrNull {
                it.name == "setConfiguration" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "android.net.IpConfiguration"
            } ?: ethernet.javaClass.methods.firstOrNull {
                it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1].name == "android.net.IpConfiguration"
            } ?: error("Ethernet configuration method not found")
            if (method.parameterTypes.size == 1) method.invoke(ethernet, configuration)
            else method.invoke(ethernet, "eth0", configuration)
            println("OK ${args[0]}")
        } catch (t: Throwable) {
            t.printStackTrace()
            kotlin.system.exitProcess(2)
        }
    }
}
