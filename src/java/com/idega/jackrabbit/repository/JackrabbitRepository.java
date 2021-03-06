package com.idega.jackrabbit.repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.jcr.Binary;
import javax.jcr.Credentials;
import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockManager;
import javax.jcr.observation.ObservationManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.core.RepositoryImpl;
import org.apache.jackrabbit.core.config.RepositoryConfig;
import org.apache.jackrabbit.value.StringValue;
import org.apache.jackrabbit.value.ValueFactoryImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;

import com.idega.builder.bean.AdvancedProperty;
import com.idega.core.accesscontrol.business.LoginDBHandler;
import com.idega.core.accesscontrol.business.LoginSession;
import com.idega.core.accesscontrol.data.LoginTable;
import com.idega.core.file.util.MimeTypeUtil;
import com.idega.idegaweb.IWMainApplication;
import com.idega.idegaweb.IWMainApplicationShutdownEvent;
import com.idega.io.ZipInstaller;
import com.idega.jackrabbit.JackrabbitConstants;
import com.idega.jackrabbit.bean.JackrabbitRepositoryItem;
import com.idega.jackrabbit.repository.access.JackrabbitAccessControlList;
import com.idega.jackrabbit.security.JackrabbitSecurityHelper;
import com.idega.jackrabbit.stream.RepositoryStream;
import com.idega.presentation.IWContext;
import com.idega.repository.RepositoryService;
import com.idega.repository.access.AccessControlList;
import com.idega.repository.authentication.AuthenticationBusiness;
import com.idega.repository.bean.RepositoryItem;
import com.idega.repository.bean.RepositoryItemVersionInfo;
import com.idega.repository.event.RepositoryEventListener;
import com.idega.user.data.bean.User;
import com.idega.util.ArrayUtil;
import com.idega.util.CoreConstants;
import com.idega.util.CoreUtil;
import com.idega.util.FileUtil;
import com.idega.util.IOUtil;
import com.idega.util.ListUtil;
import com.idega.util.StringHandler;
import com.idega.util.StringUtil;
import com.idega.util.expression.ELUtil;

/**
 * Implementation of {@link RepositoryService}
 *
 * @author valdas
 *
 */
public class JackrabbitRepository implements org.apache.jackrabbit.api.JackrabbitRepository, RepositoryService {

	private static final Logger LOGGER = Logger.getLogger(JackrabbitRepository.class.getName());

	private Repository repository;

	@Autowired
	private JackrabbitSecurityHelper securityHelper;
	@Autowired
	private AuthenticationBusiness authenticationBusiness;

	private List<RepositoryEventListener> eventListeners = new ArrayList<RepositoryEventListener>();

	private Logger getLogger() {
		return LOGGER;
	}

	private IWMainApplication getApplication() {
		IWContext iwc = CoreUtil.getIWContext();
		return iwc == null ? IWMainApplication.getDefaultIWMainApplication() : iwc.getIWMainApplication();
	}

	private User getCurrentUser() {
		IWContext iwc = CoreUtil.getIWContext();
		if (iwc != null && iwc.isLoggedOn())
			return iwc.getLoggedInUser();

		try {
			LoginSession loginSession = ELUtil.getInstance().getBean(LoginSession.class.getName());
			if (loginSession != null)
				return loginSession.getUser();
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Error getting current user", e);
		}

		return null;
	}

	@Override
	public void initializeRepository(InputStream configStream, String repositoryName) throws Exception {
		try {
			repository = RepositoryImpl.create(RepositoryConfig.create(configStream, repositoryName));
		} finally {
			IOUtil.close(configStream);
		}
	}

	@Override
	public String[] getDescriptorKeys() {
		return repository.getDescriptorKeys();
	}
	@Override
	public boolean isStandardDescriptor(String key) {
		return repository.isStandardDescriptor(key);
	}
	@Override
	public boolean isSingleValueDescriptor(String key) {
		return repository.isSingleValueDescriptor(key);
	}
	@Override
	public Value getDescriptorValue(String key) {
		return repository.getDescriptorValue(key);
	}
	@Override
	public Value[] getDescriptorValues(String key) {
		return repository.getDescriptorValues(key);
	}
	@Override
	public String getDescriptor(String key) {
		return repository.getDescriptor(key);
	}
	@Override
	public Session login(Credentials credentials, String workspaceName) throws LoginException, NoSuchWorkspaceException, RepositoryException {
		return repository.login(credentials, workspaceName);
	}
	@Override
	public Session login(Credentials credentials) throws LoginException, RepositoryException {
		return repository.login(credentials);
	}
	@Override
	public Session login(String workspaceName) throws LoginException, NoSuchWorkspaceException, RepositoryException {
		return repository.login(workspaceName);
	}
	@Override
	public Session login() throws LoginException, RepositoryException {
		return repository.login();
	}

	@Override
	public void shutdown() {
		if (repository instanceof org.apache.jackrabbit.api.JackrabbitRepository) {
			((org.apache.jackrabbit.api.JackrabbitRepository) repository).shutdown();
		}
	}
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof IWMainApplicationShutdownEvent) {
			this.shutdown();
		}
	}

	@Override
	public boolean uploadFileAndCreateFoldersFromStringAsRoot(String parentPath, String fileName, String fileContentString,	String contentType)
		throws RepositoryException {

		try {
			return uploadFile(parentPath, fileName, StringHandler.getStreamFromString(fileContentString), contentType, securityHelper.getSuperAdmin()) != null;
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error uploading file: " + fileContentString + "\n to: " + parentPath + fileName, e);
		}
		return false;
	}
	@Override
	public boolean uploadXMLFileAndCreateFoldersFromStringAsRoot(String parentPath, String fileName, String fileContentString) throws RepositoryException {
		return uploadFileAndCreateFoldersFromStringAsRoot(parentPath, fileName, fileContentString, MimeTypeUtil.MIME_TYPE_XML);
	}
	@Override
	public boolean uploadFileAndCreateFoldersFromStringAsRoot(String parentPath, String fileName, InputStream stream, String contentType) throws RepositoryException {
		return uploadFile(parentPath, fileName, stream, contentType, securityHelper.getSuperAdmin()) != null;
	}
	@Override
	public boolean uploadFile(String uploadPath, String fileName, String contentType, InputStream fileInputStream) throws RepositoryException {
		return uploadFile(uploadPath, fileName, fileInputStream, contentType, getUser()) != null;
	}
	private Node uploadFile(String parentPath, String fileName, InputStream content, String mimeType, User user, AdvancedProperty... properties)
		throws RepositoryException {

		if (parentPath == null) {
			getLogger().warning("Parent path is not defined!");
			return null;
		}
		if (StringUtil.isEmpty(fileName)) {
			getLogger().warning("File name is not defined!");
			return null;
		}
		if (content == null) {
			getLogger().warning("Input stream is invalid!");
			return null;
		}
		if (parentPath.startsWith(CoreConstants.WEBDAV_SERVLET_URI))
			parentPath = parentPath.replaceFirst(CoreConstants.WEBDAV_SERVLET_URI, CoreConstants.EMPTY);
		if (!parentPath.endsWith(CoreConstants.SLASH))
			parentPath = parentPath.concat(CoreConstants.SLASH);

		Binary binary = null;
		Session session = null;
		try {
			session = getSession(user);
			addListeners(session);
			VersionManager versionManager = session.getWorkspace().getVersionManager();

			Node root = session.getRootNode();
			Node folder = getFolderNode(root, parentPath);
			Node file = getFileNode(folder, fileName);
			Node resource = getResourceNode(file);
			if (resource == null) {
				resource = file.addNode(JcrConstants.JCR_CONTENT, JcrConstants.NT_RESOURCE);
			} else {
				//	There are previous version(s) of this file
				if (!versionManager.isCheckedOut(file.getPath()))
					versionManager.checkout(file.getPath());
			}

			mimeType = StringUtil.isEmpty(mimeType) ? MimeTypeUtil.resolveMimeTypeFromFileName(fileName) : mimeType;
			mimeType = StringUtil.isEmpty(mimeType) ? MimeTypeUtil.MIME_TYPE_APPLICATION : mimeType;
			resource.setProperty(JcrConstants.JCR_MIMETYPE, mimeType);
			resource.setProperty(JcrConstants.JCR_ENCODING, CoreConstants.ENCODING_UTF8);
			binary = ValueFactoryImpl.getInstance().createBinary(content);
			resource.setProperty(JcrConstants.JCR_DATA, binary);
			Calendar lastModified = Calendar.getInstance();
			lastModified.setTimeInMillis(System.currentTimeMillis());
			resource.setProperty(JcrConstants.JCR_LASTMODIFIED, lastModified);

			session.save();

			if (doMakeVersionable(versionManager, file))
				return file;

			return null;
		} finally {
			if (binary != null)
				binary.dispose();
			IOUtil.close(content);

			logout(session);
		}
	}

	@Override
	public VersionManager getVersionManager() throws RepositoryException {
		try {
			return getSession(getUser()).getWorkspace().getVersionManager();
		} catch (UnsupportedRepositoryOperationException e) {
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public List<RepositoryItemVersionInfo> getVersions(String parentPath, String fileName) throws RepositoryException {
		List<RepositoryItemVersionInfo> itemsInfo = new ArrayList<RepositoryItemVersionInfo>();

		Session session = null;
		try {
			User user = getUser();
			session = getSession(user);
			Node root = session.getRootNode();
			Node folder = getFolderNode(root, parentPath);
			Node file = getFileNode(folder, fileName);

			VersionManager vm = session.getWorkspace().getVersionManager();
			VersionHistory vh = vm.getVersionHistory(file.getPath());
			for (VersionIterator vi = vh.getAllVersions(); vi.hasNext();) {
				Version version = vi.nextVersion();

				for (NodeIterator nodeIter = version.getNodes(); nodeIter.hasNext();) {
					Node n = nodeIter.nextNode();
					Binary binary = getBinary(n.getPath(), session, user, false);
					if (binary != null) {
						RepositoryItemVersionInfo info = new RepositoryItemVersionInfo();
						info.setPath(version.getPath());
						info.setName(version.getName());
						info.setCreated(version.getCreated().getTime());
						info.setId(version.getIdentifier());
						info.setVersion(getVersion(version.toString()));
						itemsInfo.add(info);

						binary.dispose();
					}
				}
			}

			return itemsInfo;
		} finally {
			logout(session);
		}
	}

	private Double getVersion(String nodePath) {
		Double version = 1.0;
		if (StringUtil.isEmpty(nodePath)) {
			return version;
		}
		if (nodePath.indexOf(CoreConstants.SLASH) == -1) {
			return version;
		}

		String versionId = null;
		try {
			versionId = nodePath.substring(nodePath.lastIndexOf(CoreConstants.SLASH) + 1);
			return Double.valueOf(versionId);
		} catch (IndexOutOfBoundsException e) {
			getLogger().warning("Error while trying to get version from " + nodePath);
		} catch (NumberFormatException e) {
			getLogger().warning("Error while converting " + versionId + " to Double");
		}

		return version;
	}

	private Node getFolderNode(Node parent, String nodeName) throws RepositoryException {
		return getNode(parent, nodeName, true, JcrConstants.NT_FOLDER);
	}
	private Node getFileNode(Node parent, String nodeName) throws RepositoryException {
		return getNode(parent, nodeName, true, JcrConstants.NT_FILE);
	}
	private Node getResourceNode(Node parent) throws RepositoryException {
		return getNode(parent, JcrConstants.JCR_CONTENT, false, JcrConstants.NT_RESOURCE);
	}
	private Node getNode(Node parent, String nodeName, String type) throws RepositoryException {
		return getNode(parent, nodeName, true, type);
	}
	private Node getNode(Node parent, String nodeName, boolean createIfNotFound, String type) throws RepositoryException {
		if (nodeName.startsWith(CoreConstants.SLASH))
			nodeName = nodeName.substring(1);
		if (nodeName.endsWith(CoreConstants.SLASH))
			nodeName = nodeName.substring(0, nodeName.length() - 1);

		Node node = null;
		try {
			node = parent.getNode(nodeName);
		} catch (PathNotFoundException e) {}

		if (node == null && createIfNotFound) {
			//	Will create a node
			boolean noType = StringUtil.isEmpty(type);

			String[] pathParts = nodeName.split(CoreConstants.SLASH);
			for (int i = 0; i < pathParts.length; i++) {
				String name = pathParts[i];
				node = getNode(parent, name, false, type);
				if (node == null) {
					node = noType ? parent.addNode(name) : parent.addNode(name, type);
					if (node == null)
						throw new RepositoryException("Unable to add node " + name + " to the parent " + parent);

					setDefaultProperties(node, type);
				}

				parent = node;
			}
		}

		return node;
	}

	private boolean doMakeVersionable(VersionManager versionManager, Node node) {
		if (versionManager == null || node == null)
			return false;

		try {
			node.getSession().save();
			versionManager.checkin(node.getPath());	//	Making initial version
			return true;
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error making version for " + node, e);
		}
		return false;
	}

	private void setDefaultProperties(Node node, String nodeType) throws RepositoryException {
		List<String> defaultMixins = Arrays.asList(
				JcrConstants.MIX_VERSIONABLE,
				JcrConstants.MIX_REFERENCEABLE
		);
		for (String mixin: defaultMixins) {
			node.addMixin(mixin);
		}

		if (!StringUtil.isEmpty(nodeType))
			node.setPrimaryType(nodeType);

//		String idegaNode = null;
//		if (JcrConstants.NT_FOLDER.equals(nodeType)) {
//			idegaNode = "folder".concat(JackrabbitConstants.IDEGA_NODE);
//		} else if (JcrConstants.NT_FILE.equals(nodeType)) {
//			idegaNode = "file".concat(JackrabbitConstants.IDEGA_NODE);
//		}
//		if (idegaNode != null) {
//			try {
//				NodeDefinition def = node.getDefinition();
//				node.getProperty(JcrConstants.NT_BASE);
//				def.getDeclaringNodeType().canSetProperty(JcrConstants.NT_BASE, new StringValue(idegaNode));
//				PropertyDefinition[] propDefs = def.getDeclaringNodeType().getDeclaredPropertyDefinitions();
//				getLogger().info("Defs: " + propDefs);
//				node.setProperty(JcrConstants.JCR_PRIMARYITEMNAME, idegaNode);
//			} catch (Exception e) {
//				getLogger().log(Level.WARNING, "Error setting property " + JcrConstants.JCR_PRIMARYITEMNAME + " with value " + idegaNode + " to " + node + " with type " + nodeType, e);
//			}
//		}
	}

	private Session getSessionBySuperAdmin() throws RepositoryException {
		User admin = null;
		try {
			admin = securityHelper.getSuperAdmin();
		} catch (Exception e) {
			getLogger().severe("Administrator user can not be resolved!");
			throw new RepositoryException(e);
		}
		return getSession(admin);
	}

	@Override
	public Session getSession(User user) throws RepositoryException {
		if (user == null) {
			throw new RepositoryException("User can not be identified!");
		}

		LoginTable loginTable = LoginDBHandler.getUserLogin(user.getId());
		Credentials credentials = getCredentials(Integer.valueOf(user.getId()), loginTable.getUserPasswordInClearText());

		return repository.login(credentials);
	}

	private void logout(Session session) {
		if (session != null && session.isLive())
			session.logout();
	}

	@Override
	public InputStream getInputStream(String path) throws IOException, RepositoryException {
		return getInputStream(path, getUser());
	}
	@Override
	public InputStream getInputStreamAsRoot(String path) throws IOException, RepositoryException {
		return getInputStream(path, securityHelper.getSuperAdmin());
	}
	private InputStream getInputStream(String path, User user) throws IOException, RepositoryException {
		if (StringUtil.isEmpty(path)) {
			getLogger().warning("Path to resource is not defined!");
			return null;
		}
		if (path.startsWith(CoreConstants.WEBDAV_SERVLET_URI))
			path = path.replaceFirst(CoreConstants.WEBDAV_SERVLET_URI, CoreConstants.EMPTY);

		Session session = null;
		try {
			session = getSession(user);

			Node root = session.getRootNode();
			Node file = getNode(root, path, false, null);
			if (file == null) {
				getLogger().warning("Resource does not exist: " + path);
				return null;
			}

			String pathToStream = file.getPath();
			Binary data = getBinary(pathToStream, session, user, false);
			return getInputStream(data, pathToStream);
		} finally {
			logout(session);
		}
	}

	@Override
	public Binary getBinary(String path) throws RepositoryException {
		return getBinary(path, null, getUser(), true);
	}

	private Binary getBinary(String path, Session session, User user, boolean closeSession) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return null;

		try {
			Node file = null;
			if (session == null) {
				file = getNode(path, false);
			} else {
				file = getNode(path, false, user, session, false);
			}

			if (file == null)
				return null;

			if (session == null)
				session = file.getSession();

			Node resource = getResourceNode(file);
			Property prop = resource == null ? null : resource.getProperty(JcrConstants.JCR_DATA);
			return prop == null ? null : prop.getBinary();
		} finally {
			if (closeSession)
				logout(session);
		}
	}

	private InputStream getInputStream(Binary data, String path) throws IOException, RepositoryException {
		if (data == null) {
			getLogger().warning("Value is not set for resource!");
			return null;
		}

		return StringUtil.isEmpty(path) ? data.getStream() : new RepositoryStream(path, data.getStream());
	}

	private User getUser() throws RepositoryException {
		User user = getCurrentUser();
		if (user == null) {
			throw new RepositoryException("User must be logged in!");
		}
		return user;
	}

	@Override
	public boolean deleteAsRootUser(String path) throws RepositoryException {
		return delete(path, securityHelper.getSuperAdmin());
	}
	@Override
	public boolean delete(String path) throws RepositoryException {
		return delete(path, getUser());
	}
	@Override
	public boolean delete(String path, User user) throws RepositoryException {
		if (StringUtil.isEmpty(path)) {
			getLogger().warning("Resource is not defined!");
			return false;
		}

		Session session = null;
		try {
			session = getSession(user);
			addListeners(session);

			Node root = session.getRootNode();
			Node fileOrFolder = getNode(root, path, false, null);
			if (fileOrFolder == null) {
				getLogger().warning("Resource does not exist: " + path);
				return false;
			}

			fileOrFolder.remove();
			session.save();
			return true;
		} finally {
			logout(session);
		}
	}

	@Override
	public boolean createFolder(String path) throws RepositoryException {
		return createFolder(path, getUser());
	}
	@Override
	public boolean createFolderAsRoot(String path) throws RepositoryException {
		return createFolder(path, securityHelper.getSuperAdmin());
	}
	private boolean createFolder(String path, User user) throws RepositoryException {
		if (StringUtil.isEmpty(path)) {
			getLogger().warning("Resource path is not defined!");
			return false;
		}

		Session session = null;
		try {
			session = getSession(user);
			addListeners(session);

			Node root = session.getRootNode();
			Node folder = getNode(root, path, JcrConstants.NT_FOLDER);

			session.save();

			return folder != null;
		} finally {
			logout(session);
		}
	}

	@Override
	public AccessControlPolicy[] applyAccessControl(String absPath, AccessControlPolicy[] policies) throws RepositoryException {
		if (StringUtil.isEmpty(absPath) || ArrayUtil.isEmpty(policies)) {
			getLogger().warning("Path (" + absPath + ") or the policies (" + policies + ") are invalid");
			return null;
		}


		Session session = null;
		try {
			session = getSessionBySuperAdmin();

			//	TODO: finish up!
			/*AccessControlManager acm = session.getAccessControlManager();
			PrincipalManager pm = null;
			if (session instanceof SessionImpl) {
				pm = ((SessionImpl) session).getPrincipalManager();
			}
			for (AccessControlPolicyIterator policiesIter = acm.getApplicablePolicies(absPath); policiesIter.hasNext();) {
				AccessControlPolicy acp = policiesIter.nextAccessControlPolicy();
				if (!(acp instanceof AbstractACLTemplate)) {
					continue;
				}

				AbstractACLTemplate acl = (AbstractACLTemplate) acp;
				for (AccessControlPolicy policy: policies) {
					if (policy instanceof IWAccessControlPolicy) {
						IWAccessControlPolicy pol = (IWAccessControlPolicy) policy;
						Map<String, List<String>> prinicpalsAndPolicies = pol.getPolicies();
						for (Iterator<String> roleOrIdIter = prinicpalsAndPolicies.keySet().iterator(); roleOrIdIter.hasNext();) {
							final String roleOrId = roleOrIdIter.next();
							List<String> privilegies = prinicpalsAndPolicies.get(roleOrId);

							Principal p = new Principal() {
								@Override
								public String getName() {
									return roleOrId;
								}
							};

							int index = 0;
							Privilege[] privs = new Privilege[privilegies.size()];
							for (String privilege: privilegies) {
								privs[index] = acm.privilegeFromName(privilege);
								index++;
							}
							acl.addAccessControlEntry(p, privs);
						}
					}
				}

				acm.setPolicy(absPath, acl);
			}

			session.save();*/
		} finally {
			logout(session);
		}

		return null;
	}

	private boolean isValidPath(String absolutePath) {
		if (StringUtil.isEmpty(absolutePath) || absolutePath.indexOf(CoreConstants.SLASH) == -1)
			return false;

		return true;
	}

	private String getNodeName(String absolutePath) {
		return isValidPath(absolutePath) ? absolutePath.substring(absolutePath.lastIndexOf(CoreConstants.SLASH)) : null;
	}

	@Override
	public Node updateFileContents(String absolutePath, InputStream fileContents, boolean createFile, AdvancedProperty... properties) throws RepositoryException {
		return uploadFile(getParentPath(absolutePath), getNodeName(absolutePath), fileContents, null, getUser());
	}
	@Override
	public Node updateFileContents(String absolutePath, InputStream fileContents, AdvancedProperty... properties) throws RepositoryException {
		return updateFileContents(absolutePath, fileContents, Boolean.TRUE, properties);
	}

	@Override
	public InputStream getFileContents(String path) throws IOException, RepositoryException {
		return getFileContents(getUser(), path);
	}

	@Override
	public InputStream getFileContents(User user, String path) throws IOException, RepositoryException {
		return getInputStream(path, user);
	}

	@Override
	public Node getNode(String absolutePath) throws RepositoryException {
		return getNode(absolutePath, true);
	}
	private Node getNode(String path, boolean closeSession) throws RepositoryException {
		return getNode(path, false, closeSession);
	}

	@Override
	public Node getNodeAsRootUser(String absolutePath) throws RepositoryException {
		return getNode(absolutePath, securityHelper.getSuperAdmin());
	}

	private Node getNode(String absolutePath, User user) throws RepositoryException {
		return getNode(absolutePath, false, user, true);
	}
	private Node getNode(String absolutePath, boolean createIfNotFound, boolean closeSession) throws RepositoryException {
		return getNode(absolutePath, createIfNotFound, getUser(), closeSession);
	}
	private Node getNode(String absolutePath, boolean createIfNotFound, User user, boolean closeSession)
			throws RepositoryException {
		Session session = null;
		try {
			session = getSession(user);
			return getNode(absolutePath, createIfNotFound, user, session, closeSession);
		} finally {
			if (closeSession)
				logout(session);
		}
	}
	private Node getNode(String absolutePath, boolean createIfNotFound, User user, Session session, boolean closeSession) throws RepositoryException {
		if (!isValidPath(absolutePath) || session == null || !session.isLive())
			return null;

		try {
			Node root = session.getRootNode();
			return getNode(root, absolutePath, createIfNotFound, null);
		} finally {
			if (closeSession)
				logout(session);
		}
	}

	@Override
	public boolean setProperties(String path, com.idega.repository.bean.Property... properties)	throws RepositoryException {
		if (StringUtil.isEmpty(path) || ArrayUtil.isEmpty(properties))
			return false;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return false;

			session = node.getSession();
			addListeners(session);
			for (com.idega.repository.bean.Property property: properties) {
				Serializable value = property.getValue();
				if (value == null)
					continue;

				Value propValue = null;
				if (value instanceof String)
					propValue = new StringValue((String) value);

				if (propValue == null)
					node.setProperty(property.getKey(), value.toString());
				else
					node.setProperty(property.getKey(), propValue);
			}
			if (session != null)
				session.save();

			return true;
		} finally {
			logout(session);
		}
	}

	@Override
	public String getRepositoryConstantFolderType() {
		return JcrConstants.NT_FOLDER;
	}

	@Override
	public String getWebdavServerURL() {
		String appContext = getApplication().getApplicationContextURI();
		if (appContext.endsWith(CoreConstants.SLASH)) {
			appContext = appContext.substring(0, appContext.lastIndexOf(CoreConstants.SLASH));
		}
		return appContext.concat(CoreConstants.WEBDAV_SERVLET_URI);
	}

	@Override
	public Credentials getCredentials(String userName, String password) {
		return getCredentials(userName, password, new AdvancedProperty[] {});
	}
	public Credentials getCredentials(int userId, String password) {
		return getCredentials(String.valueOf(userId), password, new AdvancedProperty(JackrabbitConstants.REAL_USER_ID_USED, Boolean.TRUE.toString()));
	}

	private Credentials getCredentials(String userNameOrId, String password, AdvancedProperty... attributes) {
		if (StringUtil.isEmpty(userNameOrId)) {
			getLogger().warning("User name or ID is not defined!");
			return null;
		}

		SimpleCredentials credentials = new SimpleCredentials(userNameOrId, StringUtil.isEmpty(password) ? CoreConstants.EMPTY.toCharArray() : password.toCharArray());
		if (!ArrayUtil.isEmpty(attributes)) {
			for (AdvancedProperty attribute: attributes) {
				credentials.setAttribute(attribute.getId(), attribute.getValue());
			}
		}

		return credentials;
	}

	@Override
	public boolean generateUserFolders(String loginName) throws RepositoryException {
		if (StringUtil.isEmpty(loginName)) {
			getLogger().warning("User name is not defined");
			return false;
		}

		String userPath = authenticationBusiness.getUserPath(loginName);
		Node userNode = getNode(userPath, true, true);
		if (userNode == null) {
			throw new RepositoryException("Node at " + userPath + " can not be created!");
		}
		//	TODO: set access rights for userNode

//		if (!getExistence(userPath)) {
//			WebdavResource user = getWebdavResourceAuthenticatedAsRoot(userPath);
//			user.mkcolMethod();
//			user.close();
//		}

		String userHomeFolder = getUserHomeFolderPath(loginName);
		Node userHomeNode = getNode(userHomeFolder, true, false);
		if (userHomeNode == null) {
			throw new RepositoryException("Node at " + userHomeFolder + " can not be created!");
		}
		//	TODO: set access rights for userHomeNode

//		WebdavResource rootFolder = getWebdavResourceAuthenticatedAsRoot();
//		String userFolderPath = getURI(getUserHomeFolderPath(loginName));
//		rootFolder.mkcolMethod(userFolderPath);
//		rootFolder.mkcolMethod(userFolderPath + FOLDER_NAME_DROPBOX);
//		rootFolder.mkcolMethod(userFolderPath + FOLDER_NAME_PUBLIC);
//		rootFolder.close();

		try {
			updateUserFolderPrivileges(loginName);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	private String getUserHomeFolderPath(String loginName) {
		return JackrabbitConstants.PATH_USERS_HOME_FOLDERS + CoreConstants.SLASH + loginName;
	}

	private void updateUserFolderPrivileges(String loginName) throws IOException, IOException {
		//	TODO: implement!

//		String userFolderPath = getURI(getUserHomeFolderPath(loginName));
//
//		AuthenticationBusiness aBusiness = getAuthenticationBusiness();
//		String userPrincipal = aBusiness.getUserURI(loginName);
//
//		// user folder
//		AccessControlList userFolderList = getAccessControlList(userFolderPath);
//		// should be 'all' for the user himself
//		List<AccessControlEntry> userFolderUserACEs = userFolderList.getAccessControlEntriesForUsers();
//		AccessControlEntry usersPositiveAce = null;
//		AccessControlEntry usersNegativeAce = null;
//		boolean madeChangesToUserFolderList = false;
//		// Find the ace
//		for (Iterator<AccessControlEntry> iter = userFolderUserACEs.iterator(); iter.hasNext();) {
//			AccessControlEntry ace = iter.next();
//			if (ace.getPrincipal().equals(userPrincipal) && !ace.isInherited()) {
//				if (ace.isNegative()) {
//					usersNegativeAce = ace;
//				} else {
//					usersPositiveAce = ace;
//				}
//			}
//		}
//		if (usersPositiveAce == null) {
//			usersPositiveAce = new AccessControlEntry(userPrincipal, false, false, false, null, AccessControlEntry.PRINCIPAL_TYPE_USER);
//			userFolderList.add(usersPositiveAce);
//		}
//
//		if (!usersPositiveAce.containsPrivilege(IWSlideConstants.PRIVILEGE_ALL)) {
//			if (usersNegativeAce != null && usersNegativeAce.containsPrivilege(IWSlideConstants.PRIVILEGE_ALL)) {
//				// do nothing becuse this is not ment to reset permissions but
//				// to set them in the first
//				// first place and update for legacy reasons. If Administrator
//				// has closed someones user folder
//				// for some reason, this is not supposed to reset that.
//			} else {
//				usersPositiveAce.addPrivilege(IWSlideConstants.PRIVILEGE_ALL);
//				madeChangesToUserFolderList = true;
//
//				// temporary at least:
//				usersPositiveAce.setInherited(false);
//				usersPositiveAce.setInheritedFrom(null);
//				// temporary ends
//			}
//		}
//		if (madeChangesToUserFolderList) {
//			storeAccessControlList(userFolderList);
//		}
//
//		// dropbox
//		updateUsersDropboxPrivileges(userFolderPath);
//
//		// public folder
//		updateUsersPublicFolderPrivileges(userFolderPath);
	}

	@Override
	public boolean getExistence(String absolutePath) throws RepositoryException {
		if (!isValidPath(absolutePath)) {
			return false;
		}

		if (absolutePath.startsWith(CoreConstants.WEBDAV_SERVLET_URI))
			absolutePath = absolutePath.replaceFirst(CoreConstants.WEBDAV_SERVLET_URI, CoreConstants.EMPTY);

		Session session = null;
		try {
			session = getSessionBySuperAdmin();
			Node root = session.getRootNode();
			Node node = getNode(root, absolutePath, false, null);
			return node != null;
		} finally {
			logout(session);
		}
	}

	@Override
	public RepositoryItem getRepositoryItem(User user, String path) throws RepositoryException {
		if (user == null) {
			getLogger().warning("User is unknown!");
			return null;
		}

		Session session = null;
		try {
			session = getSession(user);
			Node node = getNode(path, true, user, session, false);
			if (node == null) {
				getLogger().warning("Repository item was not found: " + path);
				return null;
			}

			logout(node.getSession());

			return new JackrabbitRepositoryItem(path, user);
		} finally {
			logout(session);
		}
	}

	@Override
	public RepositoryItem getRepositoryItemAsRootUser(String path) throws RepositoryException {
		return getRepositoryItem(securityHelper.getSuperAdmin(), path);
	}

	@Override
	public Collection<RepositoryItem> getChildResources(String path) throws RepositoryException {
		return getChildNodes(getUser(), path);
	}

	@Override
	public Collection<RepositoryItem> getChildNodes(User user, String path) throws RepositoryException {
		if (user == null) {
			getLogger().warning("User is unknown!");
			return new ArrayList<RepositoryItem>();
		}

		Session session = null;
		try {
			Collection<RepositoryItem> items = new ArrayList<RepositoryItem>();

			Node node = getNode(path, false);
			if (node == null)
				return items;

			session = node.getSession();

			for (NodeIterator iter = node.getNodes(); iter.hasNext();) {
				Node childNode = iter.nextNode();
				if (isIdegaNode(childNode))
					items.add(new JackrabbitRepositoryItem(childNode.getPath(), user));
			}
			return items;
		} finally {
			logout(session);
		}
	}

	@Override
	public Collection<RepositoryItem> getChildNodesAsRootUser(String path) throws RepositoryException {
		return getChildNodes(securityHelper.getSuperAdmin(), path);
	}

	private boolean isIdegaNode(Node node) throws RepositoryException {
		if (node == null)
			return false;

		//	TODO: implement
		return true;
//		Property property = null;
//		try {
//			property = node.getProperty(JcrConstants.JCR_PRIMARYITEMNAME);
//		} catch (PathNotFoundException e) {
//			return false;
//		}
//		if (property == null)
//			return false;
//
//		Value value = property.getValue();
//		if (value == null)
//			return false;
//
//		String propValue = value.getString();
//		if (StringUtil.isEmpty(propValue))
//			return false;
//
//		return propValue.endsWith(JackrabbitConstants.IDEGA_NODE);
	}

	private boolean isCollection(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return false;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return false;

			session = node.getSession();

			if (!isIdegaNode(node))
				return false;

			if (node.hasProperty(JcrConstants.NT_FOLDER))
				return true;

			return node.isNodeType(JcrConstants.NT_FOLDER);
		} finally {
			logout(session);
		}
	}

	@Override
	public void registerRepositoryEventListener(RepositoryEventListener listener) {
		addRepositoryChangeListeners(listener);
	}

	@Override
	public String getURI(String path) {
		String serverURL = getWebdavServerURL();
		if (path.startsWith(serverURL))
			return path;

		return serverURL.concat(path);
	}

	@Override
	public boolean isFolder(String path) throws RepositoryException {
		return isCollection(path);
	}

	@Override
	public int getChildCountExcludingFoldersAndHiddenFiles(String path)
			throws RepositoryException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public List<String> getChildPathsExcludingFoldersAndHiddenFiles(String path)
			throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<String> getChildFolderPaths(String path)
			throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getParentPath(String path) {
		return isValidPath(path) ? path.substring(0, path.lastIndexOf(CoreConstants.SLASH)) : null;
	}

	@Override
	public String createUniqueFileName(String path, String scope) {
		try {
			List<RepositoryItem> siblings = getSiblingResources(path);
			if (ListUtil.isEmpty(siblings))
				return scope;

			for (RepositoryItem sibling: siblings) {
				if (sibling.getPath().endsWith(scope)) {
					scope = scope.concat(CoreConstants.UNDER).concat(String.valueOf(System.nanoTime()));
					return createUniqueFileName(path, scope);
				}
			}

			return scope;
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error making unique scope for " + scope + " in " + path, e);
		}
		return null;
	}

	@Override
	public boolean uploadZipFileContents(ZipInputStream zipStream, String uploadPath) throws RepositoryException {
		if (zipStream == null || StringUtil.isEmpty(uploadPath))
			return false;

		boolean result = true;
		ZipEntry entry = null;
		ZipInstaller zip = new ZipInstaller();
		ByteArrayOutputStream memory = null;
		InputStream is = null;
		String pathToFile = null;
		String fileName = null;
		try {
			while ((entry = zipStream.getNextEntry()) != null && result) {
				if (!entry.isDirectory()) {
					pathToFile = CoreConstants.EMPTY;
					fileName = StringHandler.removeCharacters(entry.getName(), CoreConstants.SPACE, CoreConstants.UNDER);
					fileName = StringHandler.removeCharacters(fileName, CoreConstants.BRACKET_LEFT, CoreConstants.EMPTY);
					fileName = StringHandler.removeCharacters(fileName, CoreConstants.BRACKET_RIGHT, CoreConstants.EMPTY);
					int lastSlash = fileName.lastIndexOf(CoreConstants.SLASH);
					if (lastSlash != -1) {
						pathToFile = fileName.substring(0, lastSlash + 1);
						fileName = fileName.substring(lastSlash + 1, fileName.length());
					}
					if (!fileName.startsWith(CoreConstants.DOT)) { // If not a system file
						memory = new ByteArrayOutputStream();
						zip.writeFromStreamToStream(zipStream, memory);
						is = new ByteArrayInputStream(memory.toByteArray());
						result = uploadFile(uploadPath + pathToFile, fileName, null, is);
						memory.close();
						is.close();
					}
				}
				zip.closeEntry(zipStream);
			}
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Error uploading zip file to: " + uploadPath, e);
			return false;
		} finally {
			zip.closeEntry(zipStream);
		}
		return result;
	}

	@Override
	public <T extends RepositoryItem> T getRepositoryItem(String path) throws RepositoryException {
		Node node = getNode(path);
		if (node == null)
			return null;

		@SuppressWarnings("unchecked")
		T item = (T) new JackrabbitRepositoryItem(path, getUser());
		return item;
	}

	@Override
	public boolean isHiddenFile(String name) throws RepositoryException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public String getUserHomeFolder(User user) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeProperty(String path, String name) throws RepositoryException {
		Node node = getNode(path);
		if (node == null)
			return;

		Session session = null;
		try {
			session = getSession(getUser());
			addListeners(session);
			Property property = node.getProperty(name);
			if (property != null) {
				property.remove();
				session.save();
			}
		} finally {
			logout(session);
		}
	}

	@Override
	public String getPath(String path) {
		if (path == null)
			return null;

		String uriPrefix = getWebdavServerURL();
		return path.startsWith(uriPrefix) ? path.substring(uriPrefix.length()) : path;
	}

	@Override
	public void addRepositoryChangeListeners(RepositoryEventListener listener) {
		if (listener == null || eventListeners.contains(listener))
			return;

		eventListeners.add(listener);
	}

	private void addListeners(Session session) {
		try {
			ObservationManager observationManager = session.getWorkspace().getObservationManager();
			for (RepositoryEventListener listener: eventListeners)
				observationManager.addEventListener(listener, listener.getEventTypes(), listener.getPath(), true, null, null, false);
		} catch (RepositoryException e) {
			getLogger().log(Level.WARNING, "Error registering event listeners " + eventListeners, e);
		}
//		try {
//			session = getSessionBySuperAdmin();
//			EventListenerIterator listeners = session.getWorkspace().getObservationManager().getRegisteredEventListeners();
//			for (; listeners.hasNext();) {
//				EventListener tmp = (EventListener) listeners.next();
//				LOGGER.info("Listener: " + tmp);
//			}
//		} catch (RepositoryException e) {
//			e.printStackTrace();
//		} finally {
//			logout(session);
//		}
	}

	@Override
	public OutputStream getOutputStream(String path) throws IOException, RepositoryException {
		InputStream input = getInputStream(path);
		if (input == null)
			return null;

		OutputStream output = new ByteArrayOutputStream();
		FileUtil.streamToOutputStream(input, output);
		return output;
	}

	@Override
	public AccessControlList getAccessControlList(String path) {
		JackrabbitAccessControlList acl = new JackrabbitAccessControlList(path);
		return acl;
	}

	@Override
	public void storeAccessControlList(AccessControlList acl) {
		// TODO Auto-generated method stub

	}

	@Override
	public String getName(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return null;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return null;

			session = node.getSession();
			return node.getName();
		} finally {
			logout(session);
		}
	}

	@Override
	public long getCreationDate(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return -1;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return -1;

			session = node.getSession();
			Property property = node.getProperty(JcrConstants.JCR_CREATED);
			return property == null ? 0 : property.getDate().getTime().getTime();
		} finally {
			logout(session);
		}
	}

	@Override
	public long getLastModified(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return -1;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return -1;

			session = node.getSession();
			Property property = node.getProperty(JcrConstants.JCR_LASTMODIFIED);
			return property == null ? 0 : property.getDate().getTime().getTime();
		} finally {
			logout(session);
		}
	}

	@Override
	public long getLength(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return -1;

		Binary data = null;
		Session session = null;
		try {
			User user = getUser();
			session = getSession(user);
			data = getBinary(path, session, user, false);
			return data == null ? -1 : data.getSize();
		} finally {
			if (data != null)
				data.dispose();
			logout(session);
		}
	}

	@Override
	public boolean isLocked(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return false;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return false;

			session = node.getSession();
			return node.isLocked();
		} finally {
			logout(session);
		}
	}

	@Override
	public Lock lock(String path, boolean isDeep, boolean isSessionScoped) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return null;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return null;

			session = node.getSession();
			LockManager lockManger = session.getWorkspace().getLockManager();
			return lockManger.lock(path, isDeep, isSessionScoped, -1, getUser().getId().toString());
		} finally {
			logout(session);
		}
	}

	@Override
	public void unLock(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return;

		Session session = null;
		try {
			Node node = getNode(path, false);
			if (node == null)
				return;

			session = node.getSession();
			LockManager lockManger = session.getWorkspace().getLockManager();
			lockManger.unlock(path);
		} finally {
			logout(session);
		}
	}

	@Override
	public List<RepositoryItem> getSiblingResources(String path) throws RepositoryException {
		if (StringUtil.isEmpty(path))
			return null;

		String parentPath = getParentPath(path);
		if (StringUtil.isEmpty(parentPath))
			return null;

		Session session = null;
		try {
			Node parentNode = getNode(parentPath, false);
			if (parentNode == null)
				return null;

			session = parentNode.getSession();

			User user = getUser();

			List<RepositoryItem> siblings = new ArrayList<RepositoryItem>();
			for (NodeIterator nodeIterator = parentNode.getNodes(); nodeIterator.hasNext();) {
				Node child = nodeIterator.nextNode();
				String childPath = child.getPath();

				Session childSession = child.getSession();
				try {
					if (childPath == null || path.equals(childPath))
						continue;

					if (child.hasProperty(JcrConstants.NT_FILE) || child.hasProperty(JcrConstants.NT_FOLDER))
						siblings.add(new JackrabbitRepositoryItem(childPath, user));
				} finally {
					if (!session.toString().equals(childSession.toString()))
						logout(childSession);
				}
			}
			return siblings;
		} finally {
			logout(session);
		}
	}
}