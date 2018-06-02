package amidst.mojangapi.file;

import amidst.documentation.Immutable;
import amidst.mojangapi.file.directory.DotMinecraftDirectory;
import amidst.mojangapi.file.directory.ProfileDirectory;
import amidst.mojangapi.file.directory.VersionDirectory;
import amidst.mojangapi.file.json.version.VersionJson;
import amidst.mojangapi.file.service.ClassLoaderService;
import amidst.parsing.FormatException;
import amidst.parsing.json.JsonReader;
import amidst.settings.biomeprofile.BiomeProfileSelection;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.util.Optional;

@Immutable
public class LauncherProfile {
	private final ClassLoaderService classLoaderService = new ClassLoaderService();
	private final DotMinecraftDirectory dotMinecraftDirectory;
	private final ProfileDirectory profileDirectory;
	private final VersionDirectory versionDirectory;
	private final VersionJson versionJson;
	private final boolean isVersionListedInProfile;
	private final String profileName;
	private final String remoteUrl;
	private final BiomeProfileSelection biomeProfileSelection;

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
		this.remoteUrl = null;
		this.biomeProfileSelection = null;
	}

	public LauncherProfile(String remoteUrl, BiomeProfileSelection biomeProfileSelection) {
		this.biomeProfileSelection = biomeProfileSelection;
		this.dotMinecraftDirectory = null;
		this.profileDirectory = null;
		this.versionDirectory = null;
		try {
			versionJson = JsonReader.readString("{\"id\":\"REMOTE\"}", VersionJson.class);
		} catch (FormatException e) {
			throw new RuntimeException(e);
		}
		this.isVersionListedInProfile = false;
		this.profileName = "Remote connection to "+ remoteUrl;
		this.remoteUrl = remoteUrl;
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

	public String getRemoteUrl() {
		return remoteUrl;
	}

	public BiomeProfileSelection getBiomeProfileSelection() {
		return biomeProfileSelection;
	}

	public URLClassLoader newClassLoader() throws MalformedURLException {
		return classLoaderService.createClassLoader(
				dotMinecraftDirectory.getLibraries(),
				versionJson.getLibraries(),
				versionDirectory.getJar());
	}

	public static Optional<LauncherProfile> newRemoteLauncherProfile(String remoteUrl, BiomeProfileSelection biomeProfileSelection) {
		return Optional.of(new LauncherProfile(remoteUrl, biomeProfileSelection));
	}
}
