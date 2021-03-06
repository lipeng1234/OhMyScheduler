package com.github.kfcfans.oms.worker.core.classloader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 类加载器
 *
 * @author tjq
 * @since 2020/3/23
 */
public class OhMyClassLoader extends URLClassLoader {

    public OhMyClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }
}
