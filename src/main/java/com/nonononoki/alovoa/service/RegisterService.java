package com.nonononoki.alovoa.service;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.nonononoki.alovoa.Tools;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.entity.user.UserDates;
import com.nonononoki.alovoa.entity.user.UserRegisterToken;
import com.nonononoki.alovoa.model.AlovoaException;
import com.nonononoki.alovoa.model.BaseRegisterDto;
import com.nonononoki.alovoa.model.RegisterDto;
import com.nonononoki.alovoa.repo.GenderRepository;
import com.nonononoki.alovoa.repo.UserIntentionRepository;
import com.nonononoki.alovoa.repo.UserRegisterTokenRepository;
import com.nonononoki.alovoa.repo.UserRepository;

@Service
public class RegisterService {

	@Value("${app.token.length}")
	private int tokenLength;

	@Value("${app.age.min}")
	private int minAge;

	@Value("${app.age.max}")
	private int maxAge;

	@Value("${app.age.range}")
	private int ageRange;

	@Value("${spring.profiles.active}")
	private String profile;

	@Value("${app.intention.delay}")
	private long intentionDelay;

	@Value("${app.first-name.length-max}")
	private long firstNameLengthMax;

	@Value("${app.first-name.length-min}")
	private long firstNameLengthMin;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private MailService mailService;

	@Autowired
	private PublicService publicService;

	@Autowired
	private UserRepository userRepo;

	@Autowired
	private GenderRepository genderRepo;

	@Autowired
	private UserIntentionRepository userIntentionRepo;

	@Autowired
	private UserRegisterTokenRepository registerTokenRepo;

	@Autowired
	private AuthService authService;

	@Autowired
	protected CaptchaService captchaService;

	@Autowired
	private UserService userService;

	private static final String GMAIL_EMAIL = "@gmail";

	private static final int MIN_PASSWORD_SIZE = 7;

	private static final Logger logger = LoggerFactory.getLogger(RegisterService.class);

	public String register(RegisterDto dto)
			throws NoSuchAlgorithmException, AlovoaException, MessagingException, IOException {

		boolean isValid = captchaService.isValid(dto.getCaptchaId(), dto.getCaptchaText());
		if (!isValid) {
			throw new AlovoaException(publicService.text("backend.error.captcha.invalid"));
		}

		dto.setEmail(dto.getEmail().toLowerCase());

		if (!isValidEmailAddress(dto.getEmail())) {
			throw new AlovoaException("email_invalid");
		}

		if (!profile.equals(Tools.DEV)) {
			if (dto.getEmail().contains(GMAIL_EMAIL)) {
				String[] parts = dto.getEmail().split("@");
				String cleanEmail = parts[0].replace(".", "") + "@" + parts[1];
				dto.setEmail(cleanEmail);
			}
			if (dto.getEmail().contains("+")) {
				dto.setEmail(dto.getEmail().split("[+]")[0] + "@" + dto.getEmail().split("@")[1]);
			}
		}

		// check if email is in spam mail list
		if (profile.equals(Tools.PROD)) {
			try {
				// check spam domains
				if (Tools.isTextContainingLineFromFile(Tools.TEMP_EMAIL_FILE_NAME, dto.getEmail())) {
					throw new AlovoaException(publicService.text("backend.error.register.email-spam"));
				}
			} catch (IOException e) {
				logger.error(e.getMessage());
			}
		}

		User user = userRepo.findByEmail(dto.getEmail().toLowerCase());
		if (user != null) {
			throw new AlovoaException(publicService.text("backend.error.register.email-exists"));
		}

		BaseRegisterDto baseRegisterDto = registerBase(dto, false);
		user = baseRegisterDto.getUser();

		user.setPassword(passwordEncoder.encode(dto.getPassword()));
		user = userRepo.saveAndFlush(user);

		UserRegisterToken token = createUserToken(user);
		return token.getContent();
	}

	public void registerOauth(RegisterDto dto) throws MessagingException, IOException, AlovoaException {

		String email = authService.getOauth2Email().toLowerCase();
		if (email == null) {
			throw new AlovoaException(publicService.text("email_is_null"));
		}

		User user = userRepo.findByEmail(email);
		if (user != null) {
			throw new AlovoaException(publicService.text("backend.error.register.email-exists"));
		}

		dto.setEmail(email);
		BaseRegisterDto baseRegisterDto = registerBase(dto, true);
		user = baseRegisterDto.getUser();
		user.setConfirmed(true);
		userRepo.saveAndFlush(user);

		userService.updateUserInfo(user);

		mailService.sendAccountConfirmed(user);
	}

	public UserRegisterToken createUserToken(User user) throws MessagingException, IOException {
		UserRegisterToken token = generateToken(user);
		user.setRegisterToken(token);
		user = userRepo.saveAndFlush(user);
		mailService.sendRegistrationMail(user);
		return token;
	}

	public UserRegisterToken generateToken(User user) {
		UserRegisterToken token = new UserRegisterToken();
		token.setContent(RandomStringUtils.randomAlphanumeric(tokenLength));
		token.setDate(new Date());
		token.setUser(user);
		return registerTokenRepo.saveAndFlush(token);
	}

	public User registerConfirm(String tokenString) throws MessagingException, IOException, AlovoaException {
		UserRegisterToken token = registerTokenRepo.findByContent(tokenString);

		if (token == null) {
			throw new AlovoaException("token_not_found");
		}

		User user = token.getUser();

		if (user == null) {
			throw new AlovoaException("user_not_found");
		}

		if (user.isConfirmed()) {
			throw new AlovoaException("user_not_confirmed");
		}

		user.setConfirmed(true);
		user.setRegisterToken(null);
		user = userRepo.saveAndFlush(user);

		mailService.sendAccountConfirmed(user);

		return user;
	}

	// used by normal registration and oauth
	private BaseRegisterDto registerBase(RegisterDto dto, boolean isOauth) throws AlovoaException {

		if (dto.getFirstName().length() > firstNameLengthMax || dto.getFirstName().length() < firstNameLengthMin) {
			throw new AlovoaException("name_invalid");
		}

		// check minimum age
		int userAge = Tools.calcUserAge(dto.getDateOfBirth());
		if (userAge < minAge) {
			throw new AlovoaException(publicService.text("backend.error.register.min-age"));
		}
		if (userAge > maxAge) {
			throw new AlovoaException(publicService.text("max_age_exceeded"));
		}

		if (!isOauth) {
			if (dto.getPassword().length() < MIN_PASSWORD_SIZE) {
				throw new AlovoaException("password_too_short");
			}

			if (!dto.getPassword().matches(".*\\d.*") || !dto.getPassword().matches(".*[a-zA-Z].*")) {
				throw new AlovoaException("password_too_simple");
			}
		}

		User user = new User(dto.getEmail().toLowerCase());
		user.setFirstName(dto.getFirstName());

		// default age bracket, user can change it later in their profile
		int userMinAge = userAge - ageRange;
		int userMaxAge = userAge + ageRange;
		if (userMinAge < minAge) {
			userMinAge = minAge;
		}
		if (userMaxAge > maxAge) {
			userMaxAge = maxAge;
		}

		user.setPreferedMinAge(userMinAge);
		user.setPreferedMaxAge(userMaxAge);
		user.setGender(genderRepo.findById(dto.getGender()).orElse(null));
		user.setIntention(userIntentionRepo.findById(dto.getIntention()).orElse(null));

		UserDates dates = new UserDates();
		Date today = new Date();
		dates.setActiveDate(today);
		dates.setCreationDate(today);
		dates.setDateOfBirth(dto.getDateOfBirth());
		dates.setIntentionChangeDate(new Date(today.getTime() - intentionDelay));
		dates.setMessageCheckedDate(today);
		dates.setMessageDate(today);
		dates.setNotificationCheckedDate(today);
		dates.setNotificationDate(today);
		dates.setUser(user);
		user.setDates(dates);

		// resolves hibernate issue with null Collections with orphanremoval
		// https://hibernate.atlassian.net/browse/HHH-9940
		user.setInterests(new ArrayList<>());
		user.setImages(new ArrayList<>());
		user.setDonations(new ArrayList<>());
		user.setLikes(new ArrayList<>());
		user.setLikedBy(new ArrayList<>());
		user.setConversations(new ArrayList<>());
		user.setMessageReceived(new ArrayList<>());
		user.setMessageSent(new ArrayList<>());
		user.setNotifications(new ArrayList<>());
		user.setNotificationsFrom(new ArrayList<>());
		user.setHiddenByUsers(new ArrayList<>());
		user.setHiddenUsers(new ArrayList<>());
		user.setBlockedByUsers(new ArrayList<>());
		user.setBlockedUsers(new ArrayList<>());
		user.setReported(new ArrayList<>());
		user.setReportedByUsers(new ArrayList<>());
		user.setWebPush(new ArrayList<>());

		user.setNumberProfileViews(0);
		user.setNumberSearches(0);

		user = userRepo.saveAndFlush(user);

		userService.updateUserInfo(user);

		BaseRegisterDto baseRegisterDto = new BaseRegisterDto();
		baseRegisterDto.setRegisterDto(dto);
		baseRegisterDto.setUser(user);
		return baseRegisterDto;
	}

	private static boolean isValidEmailAddress(String email) {
		try {
			InternetAddress a = new InternetAddress(email);
			a.validate();
			return true;
		} catch (AddressException ex) {
			return false;
		}
	}
}
