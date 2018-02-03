package amidst.mojangapi.file;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.util.Optional;

import amidst.documentation.Immutable;
import amidst.mojangapi.file.directory.DotMinecraftDirectory;
import amidst.mojangapi.file.directory.ProfileDirectory;
import amidst.mojangapi.file.directory.VersionDirectory;
import amidst.mojangapi.file.json.version.VersionJson;
import amidst.mojangapi.file.service.ClassLoaderService;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;

@Immutable
public class LauncherProfile {
	private final ClassLoaderService classLoaderService = new ClassLoaderService();
	private final DotMinecraftDirectory dotMinecraftDirectory;
	private final ProfileDirectory profileDirectory;
	private final VersionDirectory versionDirectory;
	private final VersionJson versionJson;
	private final boolean isVersionListedInProfile;
	private final String profileName;
	
	private final Optional<MinecraftInterface> externalInterface;

	public LauncherProfile(
			DotMinecraftDirectory dotMinecraftDirectory,
			ProfileDirectory profileDirectory,
			VersionDirectory versionDirectory,
			VersionJson versionJson,
			boolean isVersionListedInProfile,
			String profileName) {
		this.dotMinecraftDirectory = dotMinecraftDirectory;
		this.profileDirectory = profileDirectory;
		this.versionDirectory = versionDirectory;
		this.versionJson = versionJson;
		this.isVersionListedInProfile = isVersionListedInProfile;
		this.profileName = profileName;
		this.externalInterface = Optional.empty();
	}
	
	public LauncherProfile(MinecraftInterface externalInterface) {
		this.externalInterface = Optional.of(externalInterface);
		this.dotMinecraftDirectory = null;
		this.profileDirectory = null;
		this.versionDirectory = null;
		this.versionJson = new VersionJson();
		this.isVersionListedInProfile = false;
		this.profileName = "External Connection";
	}

	public String getVersionId() {
		return versionJson.getId();
	}

	/**
	 * True, iff the contained version was listed in the profile. Especially,
	 * this is false if a modded profiles was resolved via
	 * {@link UnresolvedLauncherProfile#resolveToVanilla(VersionList)}.
	 */
	public boolean isVersionListedInProfile() {
		return isVersionListedInProfile;
	}

	public String getVersionName() {
		return getVersionNamePrefix() + versionJson.getId();
	}

	private String getVersionNamePrefix() {
		if (isVersionListedInProfile) {
			return "";
		} else {
			return "*";
		}
	}

	public String getProfileName() {
		return profileName;
	}

	public File getJar() {
		return versionDirectory.getJar();
	}

	public File getSaves() {
		return profileDirectory.getSaves();
	}

	public URLClassLoader newClassLoader() throws MalformedURLException {
		return classLoaderService.createClassLoader(
				dotMinecraftDirectory.getLibraries(),
				versionJson.getLibraries(),
				versionDirectory.getJar());
	}
	
	public Optional<MinecraftInterface> getExternalInterface() {
		return externalInterface;
	}
}
