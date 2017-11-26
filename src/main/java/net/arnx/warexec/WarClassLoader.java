/*******************************************************************************
 * Copyright 2017 hidekatsu-izuno <hidekatsu.izuno@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package net.arnx.warexec;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class WarClassLoader extends URLClassLoader implements AutoCloseable {
	public static void main(String[] args) throws Exception {
		Path war = null;
		List<String> alist = new ArrayList<>();
		
		int i = 0;
		if (args.length >= 2 && "-war".equals(args[0])) {
			war = Paths.get(args[1]);
			i += 2;
		} else {
			URL url = WarClassLoader.class.getProtectionDomain().getCodeSource().getLocation();
			if ("file".equalsIgnoreCase(url.getProtocol())) {
				war = Paths.get(url.toURI());
			} else {
				throw new IllegalStateException("A war file is missing.");
			}
		}
		
		for (; i < args.length; i++) {
			alist.add(args[i]);
		}
		
		ClassLoader parent = Thread.currentThread().getContextClassLoader();
		try (WarClassLoader cl = new WarClassLoader(parent, war)) {
			Thread.currentThread().setContextClassLoader(cl);
			
			cl.execute(alist);
		} catch (Exception e) {
			throw e;
		} finally {
			Thread.currentThread().setContextClassLoader(parent);
		}
	}
	
	private final WarContext context;

	public WarClassLoader(ClassLoader parent, Path path) throws IOException {
		this(parent, new WarContext(path));
	}
	
	private WarClassLoader(ClassLoader parent, WarContext context) {
		super(context.urls.toArray(new URL[context.urls.size()]), parent, context);
		this.context = context;
	}
	
	public void execute(List<String> args) throws Exception {
		Class<?> cls = Class.forName(context.mainClassName, true, this);
		Method m = cls.getDeclaredMethod("main", String[].class);
		m.invoke(null, (Object)args.toArray(new String[args.size()]));
	}
	
	@Override
	public void close() throws IOException {
		context.close();
	}
	
	private static class WarContext implements URLStreamHandlerFactory, AutoCloseable {
		private JarFile warFile;
		private String mainClassName;
		private List<URL> urls = new ArrayList<>();
		private Map<String, JarEntry> entries = new LinkedHashMap<>();
		
		public WarContext(Path path) throws IOException {
			this.warFile = new JarFile(path.toFile());
			
			Manifest manifest = warFile.getManifest();
			if (manifest != null) {
				this.mainClassName = manifest.getMainAttributes().getValue("War-Main-Class");
			}
			if (this.mainClassName == null || this.mainClassName.isEmpty()) {
				throw new IllegalArgumentException("A War-Main-Class attribute is not found in manifest");
			}
			
			Enumeration<JarEntry> e = warFile.entries();
			boolean hasClasses = false;
			while (e.hasMoreElements()) {
				JarEntry entry = e.nextElement();
				String name = entry.getName();
				if (name.startsWith("WEB-INF/classes/") && name.endsWith(".class")) {
					entries.put(name, entry);
					hasClasses = true;
				} else if (name.startsWith("WEB-INF/lib/") && name.endsWith(".jar")) {
					entries.put(name, entry);
					urls.add(new URL("war", null, -1, name, new WarURLStreamHandler()));
				}
			}
			if (hasClasses) {
				urls.add(0, new URL("war", null, -1, "WEB-INF/classes/", new WarURLStreamHandler()));
			}
		}
		
		@Override
		public URLStreamHandler createURLStreamHandler(String protocol) {
			return new WarURLStreamHandler();
		}
		
		@Override
		public void close() throws IOException {
			warFile.close();
		}
		
		private class WarURLStreamHandler extends URLStreamHandler {
			@Override
			protected URLConnection openConnection(URL url) throws IOException {
				return new WarURLConnection(url);
			}
		}
		
		private class WarURLConnection extends URLConnection {
			protected WarURLConnection(URL url) {
				super(url);
			}

			@Override
			public void connect() throws IOException {
			}
			
			@Override
			public InputStream getInputStream() throws IOException {
				return warFile.getInputStream(entries.get(getURL().getFile()));
			}
		}
	}
}
