package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.Resource;

@Slf4j
@SpringBootApplication
public class DemoApplication implements CommandLineRunner, FaceRecognitionTerminalService.Listener {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Resource
	private FaceRecognitionTerminalService faceRecognitionTerminalService;

	@Override
	public void run(String... args) throws Exception {
		faceRecognitionTerminalService.addTerminal("abcd", "1461173");
		boolean result = faceRecognitionTerminalService.addPerson("abcd", "123456", "郑泽雄", "https://storage.xsl688.com/face/5cgjylwr2aicchpazl3c2a0ok_0.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=3uWCzRNtrKEqYRdZ%2F20210106%2F%2Fs3%2Faws4_request&X-Amz-Date=20210106T102017Z&X-Amz-Expires=432000&X-Amz-SignedHeaders=host&X-Amz-Signature=d1833310a95773141863418cc527b065b28d0ce8523a5d2dd98c4c3a50f98bd5");
		log.info("同步注册人脸结果 {}", result);
	}

	@Override
	public void onFaceRecognized(String schoolId, String userId, String imageBase64) {
		log.info("学校 {} 用户 {} 识别成功 {}", schoolId, userId, imageBase64);
	}

	@Override
	public void onFaceRegisterSuccess(String schoolId, String userId) {
		log.info("人脸注册成功! {} {}", schoolId, userId);
	}

	@Override
	public void onFaceRegisterFailed(String schoolId, String userId) {
		log.info("人脸注册失败! {} {}", schoolId, userId);
	}

}
