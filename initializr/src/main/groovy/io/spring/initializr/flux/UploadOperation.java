package io.spring.initializr.flux;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class UploadOperation implements Runnable {
	
	private static final String JDT_SERVICE_ID = "org.eclipse.flux.jdt";
	
	private static final String GET_RESOURCE_REQUEST = "getResourceRequest";
	private static final String GET_RESOURCE_RESPONSE = "getResourceResponse";
	private static final String GET_PROJECT_REQUEST = "getProjectRequest";
	private static final String GET_PROJECT_RESPONSE = "getProjectResponse";
	private static final String DISCOVER_SERVICE_REQUEST = "discoverServiceRequest";
	private static final String DISCOVER_SERVICE_RESPONSE = "discoverServiceResponse";
	private static final String START_SERVICE_REQUEST = "startServiceRequest";
	private static final String SERVICE_STATUS_CHANGE = "serviceStatusChange";
	private static final String SERVICE_REQUIRED_REQUEST = "serviceRequiredRequest";
	private static final String SERVICE_REQUIRED_RESPONSE = "serviceRequiredResponse";
	private static final String RESOURCE_STORED = "resourceStored";
	private static final String RESOURCE_CREATED = "resourceCreated";
	
	private static final List<String> statuses = Arrays.asList(new String[] {"unavailable", "available", "starting", "ready"});
	
	private static final long TIMEOUT = 5 * 1000;
	private static final long STANDARD_RESPONSE_WAIT_TIME = 1000;
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
	
	private IMessageHandler keepAliveMessageHanlder = new IMessageHandler() {
		
		public void handle(String type, JSONObject message) {
			try {
				JSONObject response = new JSONObject();
				response.put("username", message.getString("username"));
				response.put("service", message.getString("service"));
				response.put("requestSenderID", message.getString("requestSenderID"));
				mc.send(SERVICE_REQUIRED_RESPONSE, response);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		
		public String getMessageType() {
			return SERVICE_REQUIRED_REQUEST;
		}
		
		public boolean canHandle(String type, JSONObject message) {
			try {
				return JDT_SERVICE_ID.equals(message.getString("service"));
			} catch (JSONException e) {
				e.printStackTrace();
				return false;
			}
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
			locateService();
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
	}
	
	private void disconnect() {
		mc.removeMessageHandler(projectRequestHandler);
		mc.removeMessageHandler(resourceRequestHandler);
		mc.removeMessageHandler(keepAliveMessageHanlder);
		mc.disconnect();
	}
	
	private void locateService() {
		mc.addMessageHandler(keepAliveMessageHanlder);
		
		final JSONObject[] statusMessageHolder = new JSONObject[1];
		statusMessageHolder[0] = null;
		final AtomicBoolean serviceReady = new AtomicBoolean(false);
		
		IMessageHandler discoverServiceHandler = new IMessageHandler() {
			
			public void handle(String type, JSONObject message) {
				try {
					System.out.println("Discover Response HANDLE !!!!!");
					String status = message.getString("status");
					if (statusMessageHolder[0] == null) {
						statusMessageHolder[0] = message;
					} else {
						if (statuses.indexOf(statusMessageHolder[0].getString("status")) < statuses.indexOf(status)) {
							statusMessageHolder[0] = message;
						}
					}
					if (statuses.indexOf(status) > statuses.indexOf("available")) {
						serviceReady.set(true);
					}
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
			
			public String getMessageType() {
				return DISCOVER_SERVICE_RESPONSE;
			}
			
			public boolean canHandle(String type, JSONObject message) {
				System.out.println("Discover Response !!!!!");
				try {
					return JDT_SERVICE_ID.equals(message.getString("service"));
				} catch (JSONException e) {
					e.printStackTrace();
					return false;
				}
			}
		};
		
		IMessageHandler serviceReadyHandler = new IMessageHandler() {
			
			public void handle(String type, JSONObject message) {
				try {
					if ("ready".equals(message.getString("status"))) {
						serviceReady.set(true);
					}
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
			
			public String getMessageType() {
				return SERVICE_STATUS_CHANGE;
			}
			
			public boolean canHandle(String type, JSONObject message) {
				try {
					return JDT_SERVICE_ID.equals(message.getString("service"));
				} catch (JSONException e) {
					e.printStackTrace();
					return false;
				}
			}
		};
		
		mc.addMessageHandler(discoverServiceHandler);
		mc.addMessageHandler(serviceReadyHandler);
		
		try {
			JSONObject discoverRequest = new JSONObject();
			discoverRequest.put("username", username);
			discoverRequest.put("service", JDT_SERVICE_ID);
			mc.send(DISCOVER_SERVICE_REQUEST, discoverRequest);
		
			for (long time = 0; !serviceReady.get() && time < STANDARD_RESPONSE_WAIT_TIME; time += STANDARD_WAIT_PERIOD) {
				try {
					Thread.sleep(STANDARD_WAIT_PERIOD);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			mc.removeMessageHandler(discoverServiceHandler);
			
			if (statusMessageHolder[0] == null) {
				throw new RuntimeException("Flux JDT service cannot be located");
			}
			
			if ("unavailable".equals(statusMessageHolder[0].getString("status"))) {
				if (statusMessageHolder[0].has("error")) {
					throw new RuntimeException("There are no available flux JDT services: " + statusMessageHolder[0].getString("error"));
				} else {
					throw new RuntimeException("There is no JDT service available. Please try the operation later");
				}
			}
			
			if (!serviceReady.get()) {
				if ("available".equals(statusMessageHolder[0].getString("status"))) {
					JSONObject startRequest = new JSONObject();
					startRequest.put("username", username);
					startRequest.put("service", JDT_SERVICE_ID);
					startRequest.put("socketID", statusMessageHolder[0].getString("responseSenderID"));
					mc.send(START_SERVICE_REQUEST, startRequest);
				}
				
				for (long time = 0; !serviceReady.get() && time < STANDARD_RESPONSE_WAIT_TIME; time += STANDARD_WAIT_PERIOD) {
					try {
						Thread.sleep(STANDARD_WAIT_PERIOD);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			
			if (!serviceReady.get()) {
				throw new RuntimeException("Timed out waiting for JDT service");
			}
		
		} catch (JSONException e) {
			throw new RuntimeException(e.getMessage());
		} finally {
			mc.removeMessageHandler(serviceReadyHandler);		
		}
	}

}
