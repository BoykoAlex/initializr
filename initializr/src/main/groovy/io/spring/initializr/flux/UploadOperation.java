package io.spring.initializr.flux;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class UploadOperation implements Runnable {
	
	private static final String GET_RESOURCE_REQUEST = "getResourceRequest";
	private static final String GET_RESOURCE_RESPONSE = "getResourceResponse";
	private static final String GET_PROJECT_REQUEST = "getProjectRequest";
	private static final String GET_PROJECT_RESPONSE = "getProjectResponse";
	private static final long TIMEOUT = 5000;
	
	private AtomicLong lastAccessed;
	
	private static final String[] COPY_PROPS = {
		"project",
		"requestSenderID",
		"username",
		"callback_id"
	};
	
	private String username;
	private String password;
	private String host;
	private String projectName;
	private File projectDir;
	
	private MessageConnector mc;
	private Map<File, String> hashes = new HashMap<File, String>();
	
	private IMessageHandler projectRequestHandler = new IMessageHandler() {
		
		public void handle(String type, JSONObject message) {
			lastAccessed.set(System.currentTimeMillis());
			JSONArray files = new JSONArray();
			collectFiles(files, projectDir, projectDir);			
			try {
				JSONObject response = new JSONObject(message, COPY_PROPS);
				response.put("files", files);
				mc.send(GET_PROJECT_RESPONSE, response);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		
		public String getMessageType() {
			return GET_PROJECT_REQUEST;
		}
		
		public boolean canHandle(String type, JSONObject message) {
			return canHandleMessage(message);
		}
	};
	
	private IMessageHandler resourceRequestHandler = new IMessageHandler() {
		
		public void handle(String type, JSONObject message) {
			try {
				lastAccessed.set(System.currentTimeMillis());
				String relativePath = message.getString("resource");
				File file = new File(projectDir, relativePath);
				if (file.exists()) {
					JSONObject resourceResponse = new JSONObject(message, COPY_PROPS);
					resourceResponse.put("resource", relativePath);
					resourceResponse.put("timestamp", file.lastModified());
					resourceResponse.put("hash", getHash(file));
					if (file.isFile()) {
						resourceResponse.put("type", "file");
						resourceResponse.put("content", IOUtils.toString(new FileInputStream(file)));
					} else if (file.isDirectory()) {
						resourceResponse.put("type", "folder");
					}
					mc.send(GET_RESOURCE_RESPONSE, resourceResponse);
				}
			} catch (JSONException e) {
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		public String getMessageType() {
			return GET_RESOURCE_REQUEST;
		}
		
		public boolean canHandle(String type, JSONObject message) {
			return canHandleMessage(message);
		}
	};
	
	public UploadOperation(String host, String username, String password, File projectDir, String projectName) {
		this.host = host;
		this.username = username;
		this.password = password;
		this.projectDir = projectDir;
		this.projectName = projectName;
		this.lastAccessed = new AtomicLong();
	}

	public void run() {
		mc = new MessageConnector(host, username, password);
		
		while (!mc.isConnected()) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		mc.connectToChannel(username);
		while (!mc.isConnected(username)) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		mc.addMessageHandler(projectRequestHandler);
		mc.addMessageHandler(resourceRequestHandler);
		try {
			JSONObject message = new JSONObject();
			message.put("username", username);
			message.put("project", projectName);
			mc.send("projectConnected", message);
			lastAccessed.set(System.currentTimeMillis());
		} catch (JSONException e) {
			e.printStackTrace();
		}
		
		while (System.currentTimeMillis() - lastAccessed.get() < TIMEOUT) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		mc.removeMessageHandler(projectRequestHandler);
		mc.removeMessageHandler(resourceRequestHandler);
		
		mc.disconnect();
	}
	
	private boolean canHandleMessage(JSONObject message) {
		try {
			return projectName.equals(message.get("project"))
					&& username.equals(message.get("username"));
		} catch (JSONException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	private String getHash(File file) {
		String hash = hashes.get(file);
		if (hash == null) {
			try {
				hash = file.isFile() ? DigestUtils.shaHex(new FileInputStream(file)) : "0";
				hashes.put(file, hash);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return hash;
	}
	
	private void collectFiles(JSONArray jsonArray, File file, File root) {
		jsonArray.put(createJson(file, root));
		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				collectFiles(jsonArray, child, root);
			}
		}
	}
	
	private JSONObject createJson(File file, File root) {
		JSONObject projectResource = new JSONObject();
		String path = file.getPath().substring(root.getPath().length());
		if (path.startsWith(File.separator)) {
			path = path.substring(1);
		}
		try {
			projectResource.put("path", path);
			projectResource.put("timestamp", file.lastModified());
			projectResource.put("hash", getHash(file));

			if (file.isFile()) {
				projectResource.put("type", "file");
			} else if (file.isDirectory()) {
				projectResource.put("type", "folder");
			}

		} catch (JSONException e) {
			e.printStackTrace();
		}
		return projectResource;
	}

}
