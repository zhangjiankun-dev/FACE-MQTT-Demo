package com.example.demo;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class FaceRecognitionTerminalService implements MqttCallback {

	private final Listener listener;

	interface Listener {
		void onFaceRecognized(String schoolId, String userId, String imageBase64);
		void onFaceRegisterSuccess(String schoolId, String userId);
		void onFaceRegisterFailed(String schoolId, String userId);
	}

	@Value
	static class UserRegisterCommand {
		String schoolId;
		String userId;
		String imageUrl;
		String name;
	}

	static class UserRegisterCommands {
		private final List<UserRegisterCommand> list = new ArrayList<>();
		private AtomicBoolean processing = new AtomicBoolean(false);
		private final String schoolId;
		private final FaceRecognitionTerminalService service;

		public UserRegisterCommands(String schoolId, FaceRecognitionTerminalService service) {
			this.schoolId = schoolId;
			this.service = service;
		}

		public synchronized UserRegisterCommand takeCommand() {
			return list.isEmpty() ? null : list.remove(0);
		}

		public synchronized void put(UserRegisterCommand command) {
			processing.set(true);
			list.add(command);
			if (list.size() < 10) {
				processing.set(false);
				return;
			}
			List<UserRegisterCommand> commands = new ArrayList<>(10);
			for (int i = 0; i < 10; i++) {
				commands.add(list.remove(i));
			}
			service.addPersons(schoolId, commands);
		}
	}

	private final MqttClient mqttClient;

	private final Map<String, List<String>> terminals;

	private final Map<String, UserRegisterCommands> userRegistrations;

	private final ExecutorService executorService;

	@Autowired
	public FaceRecognitionTerminalService(Listener listener) {
		this.listener = listener;
		terminals = new HashMap<>();
		userRegistrations = new HashMap<>();
		executorService = Executors.newFixedThreadPool(3);
		try {
			mqttClient = new MqttClient("tcp://192.168.0.138:1883", "frts_" + System.currentTimeMillis(), new MemoryPersistence());
			MqttConnectOptions options = new MqttConnectOptions();
			options.setUserName("admin");
			options.setPassword("admin".toCharArray());
			options.setCleanSession(false);
			options.setAutomaticReconnect(true);
			options.setKeepAliveInterval(Math.toIntExact(TimeUnit.SECONDS.toSeconds(1)));
			options.setConnectionTimeout(Math.toIntExact(TimeUnit.SECONDS.toSeconds(5)));
			mqttClient.connect(options);
			mqttClient.setCallback(this);
			mqttClient.subscribe("mqtt/face/+/+", 0);
			log.info("mqtt 服务启动成功.");
		} catch (MqttException e) {
			throw new RuntimeException("mqtt 服务启动失败!", e);
		}
		executorService.execute(executeUserRegisterCommand());
	}

	protected Runnable executeUserRegisterCommand() {
		return () -> {
			while (true) {
				if (Thread.currentThread().isInterrupted()) {
					return;
				}
				userRegistrations.forEach((schoolId, userRegistrations) -> {
					UserRegisterCommand command = userRegistrations.takeCommand();
					if (command == null) {
						return;
					}
					addPerson(schoolId, command);
				});
				try {
					TimeUnit.SECONDS.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		};
	}

	public void addTerminal(String schoolId, String terminal) {
		List<String> list = terminals.getOrDefault(schoolId, new ArrayList<>());
		list.add(terminal);
		terminals.put(schoolId, list);
	}

	public void removeTerminal(String schoolId, String terminal) {}

	public void replaceTerminal(String schoolId, String terminal) {}

	protected String pickTerminal(String schoolId) {
		return Optional.ofNullable(terminals.get(schoolId))
				.orElseThrow(() -> new IllegalArgumentException("该学校尚未注册任何终端."))
				.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("该学校尚未注册任何终端."));
	}

	public void register(String schoolId, String userId, String name, String imageUrl) {
		UserRegisterCommands commands = userRegistrations.getOrDefault(schoolId, new UserRegisterCommands(schoolId, this));
		commands.put(new UserRegisterCommand(schoolId, userId, imageUrl, name));
		userRegistrations.put(schoolId, commands);
	}

	protected static String getTopic(String schoolId, String terminal) {
		return String.format("mqtt/face/%s", terminal).intern();
	}

	@Override
	public void connectionLost(Throwable cause) {
		log.error("MQTT 连接丢失", cause);
	}

	protected String whichSchool(String topic) {
		String terminal = topic.split("/")[2];
		return terminals.entrySet().stream()
				.filter(entry -> entry.getValue().stream().anyMatch(terminal::equals))
				.findFirst()
				.map(Map.Entry::getKey)
				.orElseThrow(() -> new IllegalArgumentException("未知的终端!"));
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		String payload = new String(message.getPayload());
		String schoolId = whichSchool(topic);
		log.info("MQTT 消息到达: 学校 {}, {}, {}", schoolId, topic, payload);
		JSONObject json = JSONObject.parseObject(payload);
		if (topic.endsWith("Rec")) {
			listener.onFaceRecognized(schoolId, json.getJSONObject("info").getString("customId"), json.getJSONObject("info").getString("pic"));
		} else if (topic.endsWith("Ack")) {
			ackMap.get(Long.valueOf(json.getString("messageId"))).process(json.getJSONObject("info"));
		}
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
	}

	protected void addPerson(String schoolId, UserRegisterCommand userRegisterCommand) {
		log.info("开始处理学校 {} 的人脸注册： 1.", schoolId);
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("operator", "EditPerson");
		jsonObject.put("messageId", String.valueOf(System.currentTimeMillis()));
		jsonObject.put("info", new JSONArray());

		JSONObject person = new JSONObject();
		person.put("personType", 0);
		person.put("tempCardType", 0);
		person.put("customId", userRegisterCommand.userId);
		person.put("name", userRegisterCommand.name);
		person.put("picURI", userRegisterCommand.imageUrl);
		jsonObject.getJSONArray("info").add(person);

		try {
			mqttClient.publish(getTopic(schoolId, pickTerminal(schoolId)), jsonObject.toJSONString().getBytes(StandardCharsets.UTF_8), 0, false);
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	protected void addPersons(String schoolId, List<UserRegisterCommand> userRegisterCommands) {
		log.info("开始处理学校 {} 的人脸注册： {}.", schoolId, userRegisterCommands.size());
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("operator", "AddPersons");
		jsonObject.put("messageId", String.valueOf(System.currentTimeMillis()));
		jsonObject.put("DataBegin", "BeginFlag");
		jsonObject.put("PersonNum", userRegisterCommands.size());
		jsonObject.put("info", new JSONArray());
		userRegisterCommands.forEach(cmd -> {
			JSONObject person = new JSONObject();
			person.put("customId", cmd.userId);
			person.put("name", cmd.name);
			person.put("picURI", cmd.imageUrl);
			jsonObject.getJSONArray("info").add(person);
		});
		jsonObject.put("DataEnd", "EndFlag");
		try {
			mqttClient.publish(getTopic(schoolId, pickTerminal(schoolId)), jsonObject.toJSONString().getBytes(StandardCharsets.UTF_8), 0, false);
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	static abstract class CommandAck<T> {
		private final CountDownLatch latch;
		protected abstract String getSchoolId();
		@Getter
		private T value;

		protected final long messageId;
		public CommandAck() {
			this.latch = new CountDownLatch(1);
			this.messageId = System.currentTimeMillis();
		}

		protected void await() throws InterruptedException {
			latch.await();
		}


		public void process(JSONObject data) {
			this.value = handle(data);
			latch.countDown();
		}

		protected abstract T handle(JSONObject data);

		public abstract JSONObject execute() throws InterruptedException;
	}

	static class AddPersonCommand extends CommandAck<Boolean> {

		@Getter
		private final String schoolId;
		private final String userId;
		private final String userName;
		private final String imageURI;

		public AddPersonCommand(String schoolId, String userId, String userName, String imageURI) {
			super();
			this.schoolId = schoolId;
			this.userId = userId;
			this.userName = userName;
			this.imageURI = imageURI;
		}

		@Override
		protected Boolean handle(JSONObject data) {
			return !"fail".equals(data.getString("result"));
		}

		@Override
		public JSONObject execute() throws InterruptedException {
			log.info("开始处理学校 {} 的人脸注册： 1.", schoolId);
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("operator", "EditPerson");
			jsonObject.put("messageId", String.valueOf(messageId));
			jsonObject.put("info", new JSONArray());

			JSONObject person = new JSONObject();
			person.put("personType", 0);
			person.put("tempCardType", 0);
			person.put("customId", userId);
			person.put("name", userName);
			person.put("picURI", imageURI);
			jsonObject.getJSONArray("info").add(person);
			return jsonObject;
		}
	}

	private Map<Long, CommandAck<?>> ackMap = new HashMap<>();

	public boolean addPerson(String schoolId, String userId, String name, String imageURI) {
		try {
			return execute(new AddPersonCommand(schoolId, userId, name, imageURI));
		} catch (InterruptedException e) {
			throw new RuntimeException("超时了");
		}
	}

	@SuppressWarnings("unchecked")
	protected <T> T execute(CommandAck<?> command) throws InterruptedException {
		ackMap.put(command.messageId, command);
		try {
			mqttClient.publish(getTopic(command.getSchoolId(), pickTerminal(command.getSchoolId())), command.execute().toJSONString().getBytes(StandardCharsets.UTF_8), 0, false);
			command.await();
			return (T) command.getValue();
		} catch (MqttException e) {
			e.printStackTrace();
			return null;
		}

	}
}
