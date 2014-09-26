package io.spring.initializr.flux;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

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
	private static final String GET_ALL_PROJECTS_REQUEST = "getProjectsRequest";
	private static final String GET_ALL_PROJECT_RESPONSE = "getProjectsResponse";
	private static final String RESOURCE_STORED = "resourceStored";
	private static final String RESOURCE_CREATED = "resourceCreated";
	
	
	private static final long TIMEOUT = 5 * 1000;
	private static final long STANDARD_WAIT_PERIOD = 200;
	
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
	private Map<File, String> hashes;
	private Set<File> downloadStats;
	private ReentrantLock statsLock = new ReentrantLock();
	
	
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
	
	private IMessageHandler resourceStoredHandler = new IMessageHandler() {
		
		public void handle(String type, JSONObject message) {
			try {
				String relativePath = message.getString("resource");
				updateStats(new File(projectDir, relativePath));
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		
		public String getMessageType() {
			return RESOURCE_STORED;
		}
		
		public boolean canHandle(String type, JSONObject message) {
			return canHandleMessage(message);
		}
	};
	
	private IMessageHandler resourceCreatedHandler = new IMessageHandler() {
		
		public void handle(String type, JSONObject message) {
			try {
				String relativePath = message.getString("resource");
				updateStats(new File(projectDir, relativePath));
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		
		public String getMessageType() {
			return RESOURCE_CREATED;
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
		this.downloadStats = new HashSet<File>();
		this.hashes = new HashMap<File, String>();
	}
	
	private void updateStats(File file) {
		statsLock.lock();
		try {
			downloadStats.remove(file);
		} finally {
			statsLock.unlock();
		}
	}

	public void run() {
		storeStats(projectDir);
		try {
			connect();
			validate();
			upload();
		} finally {
			disconnect();
		}
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
	
	private void storeStats(File file) {
		downloadStats.add(file);
		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				storeStats(child);
			}
		}
	}
	
	private void connect() {
		mc = new MessageConnector(host, username, password);
		while (!mc.isConnected()) {
			try {
				Thread.sleep(STANDARD_WAIT_PERIOD);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		mc.connectToChannel(username);
		while (!mc.isConnected(username)) {
			try {
				Thread.sleep(STANDARD_WAIT_PERIOD);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void validate() {
		final HashSet<String> projects = new HashSet<String>();
		final ReentrantLock lock = new ReentrantLock();
		
		IMessageHandler projectsResponseHandler = new IMessageHandler() {
			
			public void handle(String type, JSONObject message) {
				try {
					JSONArray array = message.getJSONArray("projects");
					if (array != null) {
						lock.lock();
						try {
							for (int i = 0; i < array.length(); i++) {
								JSONObject obj = array.getJSONObject(i);
								if (obj != null) {
									projects.add(obj.getString("name"));
								}
							}
						} finally {
							lock.unlock();
						}
					}
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
			
			public String getMessageType() {
				return GET_ALL_PROJECT_RESPONSE;
			}
			
			public boolean canHandle(String type, JSONObject message) {
				return true;
			}
		};
		
		mc.addMessageHandler(projectsResponseHandler);
		
		try {
			JSONObject requestProjects = new JSONObject();
			requestProjects.put("username", username);
			mc.send(GET_ALL_PROJECTS_REQUEST, requestProjects);
			
			for (long counter = 0; counter < TIMEOUT; counter+=STANDARD_WAIT_PERIOD) {
				try {
					Thread.sleep(STANDARD_WAIT_PERIOD);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				lock.lock();
				if (!projects.isEmpty()) {
					mc.removeMessageHandler(projectsResponseHandler);
					counter = TIMEOUT;
					if (projects.contains(projectName)) {
						throw new DuplicateProjectException("Project with name '"+ projectName + "' already exists.", projectName, projects);
					}
				}
				lock.unlock();
			}
			
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}
	
	private boolean doneUploading() {
		statsLock.lock();
		try {
			return downloadStats.isEmpty();
		} finally {
			statsLock.unlock();
		}
	}
	
	private void upload() {
		mc.addMessageHandler(projectRequestHandler);
		mc.addMessageHandler(resourceRequestHandler);
		mc.addMessageHandler(resourceStoredHandler);
		mc.addMessageHandler(resourceCreatedHandler);
		try {
			JSONObject message = new JSONObject();
			message.put("username", username);
			message.put("project", projectName);
			mc.send("projectConnected", message);
			lastAccessed.set(System.currentTimeMillis());
		} catch (JSONException e) {
			e.printStackTrace();
		}
		
		while (!doneUploading() && System.currentTimeMillis() - lastAccessed.get() < TIMEOUT) {
			try {
				Thread.sleep(STANDARD_WAIT_PERIOD);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		mc.removeMessageHandler(projectRequestHandler);
		mc.removeMessageHandler(resourceRequestHandler);
		mc.removeMessageHandler(resourceCreatedHandler);
		mc.removeMessageHandler(resourceStoredHandler);
	}
	
	private void disconnect() {
		mc.disconnect();
	}
	
}
