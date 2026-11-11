package com.hz.crm.whatsapp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.security.AccessService;
import com.hz.crm.security.CrmUserDetails;
import com.hz.crm.user.LoginIdentifiers;
import com.hz.crm.user.Role;

import jakarta.validation.constraints.Size;

/**
 * Website side of the WhatsApp assistant: link / unlink the signed-in user's WhatsApp number, and (while there is no
 * real WhatsApp number yet) a test chat that talks to the assistant exactly as WhatsApp would.
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WaController {

	public record Status(boolean linked, String phone, LocalDateTime linkedAt, boolean testChat) {
	}

	public record CodeView(String code, LocalDateTime expiresAt) {
	}

	/** phone: the WhatsApp number the test chat pretends to write from; text or replyId: what is sent */
	public record TestMessage(@Size(max = 25) String phone, @Size(max = 4096) String text, @Size(max = 100) String replyId) {
	}

	private final WaLinkService links;
	private final WaAssistant assistant;
	private final AccessService access;
	private final boolean testChat;

	public WaController(WaLinkService links, WaAssistant assistant, AccessService access,
			@Value("${crm.whatsapp.test-chat:true}") boolean testChat) {
		this.links = links;
		this.assistant = assistant;
		this.access = access;
		this.testChat = testChat;
	}

	private CrmUserDetails me() {
		CrmUserDetails me = access.current();
		if (me.getRole() == Role.SUPER_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The WhatsApp assistant is for company users");
		}
		return me;
	}

	@GetMapping("/status")
	public Status status() {
		CrmUserDetails me = me();
		return links.linkOf(me.getId()).map(l -> new Status(true, l.getPhone(), l.getLinkedAt(), testChat))
				.orElse(new Status(false, null, null, testChat));
	}

	/** A one-time code (10 minutes) to send from WhatsApp; any earlier code of this user stops working. */
	@PostMapping("/link-code")
	public CodeView linkCode() {
		WaLinkService.Code c = links.newCode(me().getId());
		return new CodeView(c.code(), c.expiresAt());
	}

	@DeleteMapping("/link")
	public ResponseEntity<Void> unlink() {
		links.unlink(me().getId());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Test chat. You may only write as your own linked number, or as a number nobody has linked yet (to try the
	 * linking step), so the test chat can never act as someone else.
	 */
	@PostMapping("/test-chat")
	public List<WaMessage> testChat(@RequestBody TestMessage msg) {
		if (!testChat) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		CrmUserDetails me = me();
		String phone = LoginIdentifiers.normalizeMobile(msg.phone());
		if (phone == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the WhatsApp number to test with");
		}
		links.linkOfPhone(phone).filter(l -> !l.getUserId().equals(me.getId())).ifPresent(l -> {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That number is linked to another account");
		});
		boolean hasText = msg.text() != null && !msg.text().isBlank();
		boolean hasReply = msg.replyId() != null && !msg.replyId().isBlank();
		if (!hasText && !hasReply) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to send");
		}
		return assistant.handle(phone, new WaMessage.Incoming(hasText ? msg.text() : null, hasReply ? msg.replyId() : null));
	}

	@GetMapping("/help")
	public Map<String, Object> help() {
		return Map.of("codeMinutes", WaLinkService.CODE_LIFETIME.toMinutes());
	}
}
