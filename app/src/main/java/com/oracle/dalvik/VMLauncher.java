package com.oracle.dalvik;

/**
 * Same constraint as net.kdt.pojavlaunch.utils.JREUtils — this package and
 * class name are hard-bound into libpojavexec.so's compiled JNI symbol
 * (Java_com_oracle_dalvik_VMLauncher_launchJVM), so they can't be renamed.
 * See JREUtils.java for the full explanation.
 *
 * launchJVM runs a full JVM main() inside this process using the given
 * argv — args[0] should be "java" per the C convention (argv[0] is the
 * program name), followed by JVM flags, classpath, main class, and game args,
 * exactly like invoking `java <args>` from a command line.
 *
 * @return the process's exit code (0 = clean exit)
 */
public final class VMLauncher {
    private VMLauncher() {
    }
    public static native int launchJVM(String[] args);
}
