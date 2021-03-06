package dk.schneiderelectric.dcimteam.auth0.impl;

import com.atlassian.bandana.BandanaContext;
import com.atlassian.bandana.BandanaManager;
import com.atlassian.confluence.event.events.security.LoginEvent;
import com.atlassian.confluence.security.login.LoginManager;
import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext;
import com.atlassian.confluence.user.ConfluenceAuthenticator;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.user.GroupManager;
import com.atlassian.user.impl.DefaultUser;
import com.atlassian.user.security.password.Credential;
import com.auth0.Auth0User;
import com.atlassian.user.Group;
import bucket.user.LicensingException;
import dk.schneiderelectric.dcimteam.auth0.api.AuthService;
import dk.schneiderelectric.dcimteam.auth0.api.model.Configuration;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.atlassian.seraph.auth.LoginReason;

/**
 * Extend the ConfluenceAuthenticator in order to access the
 * putPrincipalInSessionContext method
 * 
 * @author chmod
 *
 */
@Scanned
@ExportAsService({ AuthService.class })
@Named("authServiceImpl")
public class AuthServiceImpl extends ConfluenceAuthenticator implements AuthService {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private final static Log log = LogFactory.getLog(AuthServiceImpl.class);
	private BandanaContext ctx = new ConfluenceBandanaContext();
	@ComponentImport
	private final TransactionTemplate transactionTemplate;
	@ComponentImport
	private final UserAccessor userAccessor;
	@ComponentImport
	private final LoginManager loginManager;
	@ComponentImport
	private final EventPublisher eventPublisher;
	@ComponentImport
	private final GroupManager groupManager;
	@ComponentImport
	private final BandanaManager bandanaManager;

	@Inject
	public AuthServiceImpl(final UserAccessor userAccessor, TransactionTemplate transactionTemplate,
			LoginManager loginManager, EventPublisher eventPublisher, GroupManager groupManager,
			BandanaManager bandanaManager) {
		this.userAccessor = userAccessor;
		this.transactionTemplate = transactionTemplate;
		this.eventPublisher = eventPublisher;
		this.loginManager = loginManager;
		this.bandanaManager = bandanaManager;
		this.groupManager = groupManager;
	}

	@Override
	public Optional<ConfluenceUser> createUser(final String username, final String fullName, final String emailAddress) {
		if (username != null && fullName != null && emailAddress != null) {
			return transactionTemplate.execute(new TransactionCallback<Optional<ConfluenceUser>>() {
				@Override
				public Optional<ConfluenceUser> doInTransaction() {
					try {
						ConfluenceUser newlyCreatedUser = userAccessor
								.createUser(new DefaultUser(username, fullName, emailAddress), Credential.NONE);
						Group defaultGroup = groupManager.getGroup("confluence-users");
						groupManager.addMembership(defaultGroup, newlyCreatedUser);
						return Optional.of(newlyCreatedUser);
					} catch (LicensingException le) {
						log.error("Cannot create user '" + username + "'!", le);
						throw le;
					} catch (Throwable t) {
						log.error("Failed to create user '" + username + "'!", t);
					}
					return Optional.empty();
				}
			});
		} else {
			if (log.isDebugEnabled())
				log.debug("Got a null at either username:" + username + " or fullName:" + fullName
						+ " or email address:" + emailAddress);
			return Optional.empty();
		}
	}

	@Override
	public void auth(Auth0User auth0User, HttpServletRequest request, HttpServletResponse response) {
		ConfluenceUser user = getUserByUsername(auth0User.getEmail());
		if (user == null) {
			createUser(auth0User.getEmail(), auth0User.getName(), auth0User.getEmail());
		} else {
			try {
				String remoteIP = request.getRemoteAddr();
				String remoteHost = request.getRemoteHost();
				putPrincipalInSessionContext(request, user);
				loginManager.onSuccessfulLoginAttempt(user.getName(), request);
				eventPublisher.publish(new LoginEvent(this, user.getName(), request.getSession().getId(), remoteHost,
						remoteIP, LoginEvent.UNKNOWN));
				LoginReason.OK.stampRequestResponse(request, response);
			} catch (NullPointerException npe) {
				npe.printStackTrace();
			}
		}
	}

	private void login(ConfluenceUser user, HttpServletRequest request, HttpServletResponse response) {
		try {
	    		String remoteIP = request.getRemoteAddr();
	    		String remoteHost = request.getRemoteHost();
	    		putPrincipalInSessionContext(request, user);
	    		loginManager.onSuccessfulLoginAttempt(user.getName(), request);
	    		eventPublisher.publish(new LoginEvent(this, user.getName(), request.getSession().getId(), remoteHost,remoteIP, LoginEvent.UNKNOWN));
			LoginReason.OK.stampRequestResponse(request, response);
		} catch (NullPointerException npe) {
	    		npe.printStackTrace();
		}
    }

	private ConfluenceUser getUserByUsername(String username) {
		return userAccessor.getUserByName(username);
	}

	@Override
	public Optional<Configuration> loadConfig() {
		Optional<Configuration> configuration = Optional
				.<Configuration> ofNullable((Configuration) bandanaManager.getValue(ctx, AuthService.BANDANA_CTX));
		return configuration;
	}

	@Override
	public void saveConfig(Configuration config) {
		bandanaManager.setValue(ctx, AuthService.BANDANA_CTX, config);
	}
}
