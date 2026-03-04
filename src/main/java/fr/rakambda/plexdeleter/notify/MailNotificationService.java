package fr.rakambda.plexdeleter.notify;

import ch.digitalfondue.mjml4j.Mjml4j;
import com.googlecode.htmlcompressor.compressor.HtmlCompressor;
import fr.rakambda.plexdeleter.api.tautulli.data.AudioMediaPartStream;
import fr.rakambda.plexdeleter.api.tautulli.data.GetMetadataResponse;
import fr.rakambda.plexdeleter.api.tautulli.data.MediaInfo;
import fr.rakambda.plexdeleter.api.tautulli.data.SubtitlesMediaPartStream;
import fr.rakambda.plexdeleter.config.ApplicationConfiguration;
import fr.rakambda.plexdeleter.config.MailConfiguration;
import fr.rakambda.plexdeleter.notify.context.MediaMetadataContext;
import fr.rakambda.plexdeleter.notify.context.MetadataProviderInfo;
import fr.rakambda.plexdeleter.service.LanguageFlagService;
import fr.rakambda.plexdeleter.service.ThymeleafService;
import fr.rakambda.plexdeleter.service.WatchService;
import fr.rakambda.plexdeleter.storage.entity.MediaEntity;
import fr.rakambda.plexdeleter.storage.entity.MediaRequirementEntity;
import fr.rakambda.plexdeleter.storage.entity.NotificationEntity;
import fr.rakambda.plexdeleter.storage.entity.UserGroupEntity;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import static fr.rakambda.plexdeleter.storage.entity.MediaEntity.COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX;

@Slf4j
@Service
public class MailNotificationService extends AbstractNotificationService{
	private final JavaMailSender emailSender;
	private final MailConfiguration mailConfiguration;
	private final MessageSource messageSource;
	private final SpringTemplateEngine templateEngine;
	private final ThymeleafService thymeleafService;
	private final LanguageFlagService languageFlagService;
	private final HtmlCompressor htmlCompressor;
	
	@Autowired
	public MailNotificationService(JavaMailSender emailSender, ApplicationConfiguration applicationConfiguration, MessageSource messageSource, WatchService watchService, SpringTemplateEngine templateEngine, ThymeleafService thymeleafService, LanguageFlagService languageFlagService){
		super(watchService, messageSource);
		this.emailSender = emailSender;
		this.messageSource = messageSource;
		mailConfiguration = applicationConfiguration.getMail();
		this.templateEngine = templateEngine;
		this.thymeleafService = thymeleafService;
		this.languageFlagService = languageFlagService;
		
		htmlCompressor = new HtmlCompressor();
		htmlCompressor.setRemoveIntertagSpaces(true);
		htmlCompressor.setRemoveQuotes(false);
		htmlCompressor.setCompressCss(false);
	}
	
	public void notifyWatchlist(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull Collection<MediaRequirementEntity> requirements) throws MessagingException, UnsupportedEncodingException{
		var locale = userGroupEntity.getLocaleAsObject();
		
		var availableMedia = requirements.stream()
				.map(MediaRequirementEntity::getMedia)
				.filter(m -> m.getStatus().isFullyDownloaded())
				.sorted(COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX)
				.toList();
		var downloadingMedia = requirements.stream()
				.map(MediaRequirementEntity::getMedia)
				.filter(m -> m.getStatus().isDownloadStarted() && !m.getStatus().isFullyDownloaded())
				.sorted(COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX)
				.toList();
		var notYetAvailableMedia = requirements.stream()
				.map(MediaRequirementEntity::getMedia)
				.filter(m -> !m.getStatus().isDownloadStarted())
				.sorted(COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX)
				.toList();
		
		if(availableMedia.isEmpty() && downloadingMedia.isEmpty()){
			log.info("No medias eligible to notify");
			return;
		}
		
		sendMail(notification, "mail.watchlist.subject", locale, "mail/watchlist.html", context -> {
			context.setLocale(userGroupEntity.getLocaleAsObject());
			context.setVariable("service", this);
			context.setVariable("thymeleafService", thymeleafService);
			context.setVariable("userGroup", userGroupEntity);
			context.setVariable("availableMedias", availableMedia);
			context.setVariable("downloadingMedias", downloadingMedia);
			context.setVariable("notYetAvailableMedias", notYetAvailableMedia);
		}, message -> {}, availableMedia, downloadingMedia, notYetAvailableMedia);
	}
	
	public void notifyRequirementAdded(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext) throws MessagingException, UnsupportedEncodingException{
		notifyMediaDetailed(notification, userGroupEntity, media, mediaMetadataContext, "mail.requirement.added.title", "mail.requirement.added.subject", false);
	}
	
	public void notifyMediaAvailable(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext) throws MessagingException, UnsupportedEncodingException{
		notifyMediaDetailed(notification, userGroupEntity, media, mediaMetadataContext, "mail.media.available.title", "mail.media.available.subject", true);
	}
	
	public void notifyMediaDeleted(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext) throws MessagingException, UnsupportedEncodingException{
		notifyMediaDetailed(notification, userGroupEntity, media, mediaMetadataContext, "mail.media.deleted.title", "mail.media.deleted.subject", false);
	}
	
	public void notifyMediaWatched(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext) throws MessagingException, UnsupportedEncodingException{
		notifyMediaDetailed(notification, userGroupEntity, media, mediaMetadataContext, "mail.media.watched.title", "mail.media.watched.subject", false);
	}
	
	public void notifyRequirementManuallyWatched(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext) throws MessagingException, UnsupportedEncodingException{
		notifyMediaDetailed(notification, userGroupEntity, media, mediaMetadataContext, "mail.requirement.manually-watched.title", "mail.requirement.manually-watched.subject", false);
	}
	
	public void notifyRequirementManuallyAbandoned(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext) throws MessagingException, UnsupportedEncodingException{
		notifyMediaDetailed(notification, userGroupEntity, media, mediaMetadataContext, "mail.requirement.manually-abandoned.title", "mail.requirement.manually-abandoned.subject", false);
	}
	
	public void notifyMediaAdded(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @Nullable MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext) throws MessagingException, UnsupportedEncodingException{
		notifyMediaDetailed(notification, userGroupEntity, media, mediaMetadataContext, "mail.media.added.title", "mail.media.added.subject", true);
	}
	
	private void notifyMediaDetailed(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @Nullable MediaEntity media, @Nullable MediaMetadataContext mediaMetadataContext, @Nullable String mailTitleKey, @NonNull String subjectKey, boolean canAddToWhitelist) throws MessagingException, UnsupportedEncodingException{
		var locale = userGroupEntity.getLocaleAsObject();
		var metadata = Optional.ofNullable(mediaMetadataContext).map(MediaMetadataContext::getMetadata);
		
		var mediaSeason = metadata.map(m -> getMediaSeason(m, locale)).orElse(null);
		var releaseDate = metadata
				.map(GetMetadataResponse::getOriginallyAvailableAt)
				.map(DATE_FORMATTER::format)
				.orElse(null);
		var audioLanguages = metadata.stream()
				.flatMap(m -> getMediaStreams(m, AudioMediaPartStream.class))
				.map(AudioMediaPartStream::getAudioLanguageCode)
				.filter(Objects::nonNull)
				.distinct()
				.map(s -> Objects.equals(s, "") ? "unknown" : s)
				.sorted()
				.map(s -> new LanguageInfo("locale.%s".formatted(s), languageFlagService.getFlagUrl(s)))
				.toList();
		var subtitleLanguages = metadata.stream()
				.flatMap(m -> getMediaStreams(m, SubtitlesMediaPartStream.class))
				.map(SubtitlesMediaPartStream::getSubtitleLanguageCode)
				.filter(Objects::nonNull)
				.distinct()
				.map(s -> Objects.equals(s, "") ? "unknown" : s)
				.sorted()
				.map(s -> new LanguageInfo("locale.%s".formatted(s), languageFlagService.getFlagUrl(s)))
				.toList();
		var resolutions = metadata.stream()
				.map(GetMetadataResponse::getMediaInfo)
				.flatMap(Collection::stream)
				.map(MediaInfo::getVideoFullResolution)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		var bitrates = metadata.stream()
				.map(GetMetadataResponse::getMediaInfo)
				.flatMap(Collection::stream)
				.map(MediaInfo::getBitrate)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		var title = Optional.ofNullable(mediaMetadataContext)
				.flatMap(m -> m.getTitle(locale))
				.or(() -> metadata.map(GetMetadataResponse::getFullTitle))
				.or(() -> Optional.ofNullable(media).map(MediaEntity::getName))
				.orElse(null);
		var summary = Optional.ofNullable(mediaMetadataContext)
				.flatMap(m -> m.getSummary(locale))
				.or(() -> metadata.map(GetMetadataResponse::getSummary))
				.orElse(null);
		var genres = Optional.ofNullable(mediaMetadataContext)
				.flatMap(m -> m.getGenres(messageSource, locale))
				.or(() -> metadata.map(GetMetadataResponse::getGenres))
				.orElseGet(List::of);
		var metadataProviderInfos = Stream.concat(Optional.ofNullable(mediaMetadataContext)
						.stream()
						.map(MediaMetadataContext::getMetadataProviderInfo)
						.flatMap(Collection::stream),
				Stream.of(
						Optional.ofNullable(media).stream()
								.map(thymeleafService::getMediaPlexUrl)
								.filter(Objects::nonNull)
								.map(url -> new MetadataProviderInfo("Plex", url)),
						Optional.ofNullable(media).stream()
								.map(thymeleafService::getMediaSeerrUrl)
								.filter(Objects::nonNull)
								.map(url -> new MetadataProviderInfo("Seerr", url))
				).flatMap(Function.identity())
		).toList();
		
		var posterData = Optional.ofNullable(mediaMetadataContext).flatMap(MediaMetadataContext::getPosterData);
		var mediaPosterResourceName = "mediaPosterResourceName";
		
		var suggestAddRequirementId = Optional.ofNullable(media)
				.filter(m -> Optional.ofNullable(m.getRequirements()).stream()
						.flatMap(Collection::stream)
						.map(MediaRequirementEntity::getGroup)
						.map(UserGroupEntity::getId)
						.noneMatch(group -> Objects.equals(group, userGroupEntity.getId())))
				.map(MediaEntity::getId)
				.orElse(null);
		
		var serverTags = Optional.ofNullable(mediaMetadataContext)
				.flatMap(c -> c.getServerTags(media))
				.orElseGet(List::of);
		
		var duration = metadata
				.map(GetMetadataResponse::getDuration)
				.map(Duration::ofMillis)
				.map(this::getMediaDuration)
				.orElse(null);
		
		sendMail(notification, subjectKey, locale, "mail/media-detailed.html", context -> {
			context.setLocale(userGroupEntity.getLocaleAsObject());
			context.setVariable("thymeleafService", thymeleafService);
			context.setVariable("mailTitleKey", mailTitleKey);
			context.setVariable("mediaTitle", title);
			context.setVariable("mediaSeason", mediaSeason);
			context.setVariable("mediaSummary", summary);
			context.setVariable("mediaReleaseDate", releaseDate);
			context.setVariable("mediaActors", metadata.stream().map(GetMetadataResponse::getActors).flatMap(Collection::stream).limit(20).toList());
			context.setVariable("mediaGenres", genres);
			context.setVariable("mediaDuration", duration);
			context.setVariable("mediaPosterResourceName", posterData.isPresent() ? mediaPosterResourceName : null);
			context.setVariable("mediaAudios", audioLanguages);
			context.setVariable("mediaSubtitles", subtitleLanguages);
			context.setVariable("mediaResolutions", resolutions);
			context.setVariable("mediaBitrates", bitrates);
			context.setVariable("mediaServerTags", userGroupEntity.getCanViewServerTags() ? serverTags : List.of());
			context.setVariable("suggestAddRequirementId", canAddToWhitelist ? suggestAddRequirementId : null);
			context.setVariable("metadataProvidersInfo", metadataProviderInfos);
		}, message -> {
			if(posterData.isPresent()){
				message.addInline(mediaPosterResourceName, new ByteArrayResource(posterData.get()), "image/jpeg");
			}
		}, List.of());
	}
	
	@SafeVarargs
	private void sendMail(
			@NonNull NotificationEntity notification,
			@NonNull String subjectKey,
			@NonNull Locale locale,
			@NonNull String template,
			@NonNull ContextFiller contextFiller,
			@NonNull MessageFiller messageFiller,
			@NonNull List<MediaEntity>... mediasForResources
	) throws MessagingException, UnsupportedEncodingException{
		var mimeMessage = emailSender.createMimeMessage();
		var mailHelper = new MimeMessageHelper(mimeMessage, true, "utf-8");
		
		mailHelper.setFrom(mailConfiguration.getFromAddress(), mailConfiguration.getFromName());
		mailHelper.setTo(notification.getValue().split(","));
		if(Objects.nonNull(mailConfiguration.getBccAddresses()) && !mailConfiguration.getBccAddresses().isEmpty()){
			mailHelper.setBcc(mailConfiguration.getBccAddresses().toArray(new String[0]));
		}
		
		var seerrLogoResourceName = "seerrLogoResourceName";
		var plexLogoResourceName = "plexLogoResourceName";
		var tmdbLogoResourceName = "tmdbLogoResourceName";
		var tvdbLogoResourceName = "tvdbLogoResourceName";
		var traktLogoResourceName = "traktLogoResourceName";
		
		var hasSeerrLink = hasAnyMediaValueNotNull(MediaEntity::getSeerrId, mediasForResources);
		var hasPlexLink = hasAnyMediaValueNotNull(MediaEntity::getPlexId, mediasForResources);
		var hasTmdbLink = hasAnyMediaValueNotNull(MediaEntity::getTmdbId, mediasForResources);
		var hasTvdbLink = hasAnyMediaValueNotNull(MediaEntity::getTvdbId, mediasForResources);
		var hasTraktLink = hasAnyMediaValueNotNull(MediaEntity::getTmdbId, mediasForResources);
		
		var seerrLogoData = hasSeerrLink ? getSeerrLogoBytes() : Optional.<byte[]> empty();
		var plexLogoData = hasPlexLink ? getPlexLogoBytes() : Optional.<byte[]> empty();
		var tmdbLogoData = hasTmdbLink ? getTmdbLogoBytes() : Optional.<byte[]> empty();
		var tvdbLogoData = hasTvdbLink ? getTvdbLogoBytes() : Optional.<byte[]> empty();
		var traktLogoData = hasTraktLink ? getTraktLogoBytes() : Optional.<byte[]> empty();
		
		var context = new Context();
		context.setVariable("seerrLogoResourceName", seerrLogoData.isPresent() ? seerrLogoResourceName : null);
		context.setVariable("plexLogoResourceName", plexLogoData.isPresent() ? plexLogoResourceName : null);
		context.setVariable("tmdbLogoResourceName", tmdbLogoData.isPresent() ? tmdbLogoResourceName : null);
		context.setVariable("tvdbLogoResourceName", tvdbLogoData.isPresent() ? tvdbLogoResourceName : null);
		context.setVariable("traktLogoResourceName", traktLogoData.isPresent() ? traktLogoResourceName : null);
		
		contextFiller.accept(context);
		
		mailHelper.setSubject(messageSource.getMessage(subjectKey, new Object[0], locale));
		mailHelper.setText(renderMail(templateEngine.process(template, context), locale), true);
		
		if(seerrLogoData.isPresent()){
			mailHelper.addInline(seerrLogoResourceName, new ByteArrayResource(seerrLogoData.get()), "image/png");
		}
		if(plexLogoData.isPresent()){
			mailHelper.addInline(plexLogoResourceName, new ByteArrayResource(plexLogoData.get()), "image/png");
		}
		if(tmdbLogoData.isPresent()){
			mailHelper.addInline(tmdbLogoResourceName, new ByteArrayResource(tmdbLogoData.get()), "image/png");
		}
		if(tvdbLogoData.isPresent()){
			mailHelper.addInline(tvdbLogoResourceName, new ByteArrayResource(tvdbLogoData.get()), "image/png");
		}
		if(traktLogoData.isPresent()){
			mailHelper.addInline(traktLogoResourceName, new ByteArrayResource(traktLogoData.get()), "image/png");
		}
		
		messageFiller.accept(mailHelper);
		
		emailSender.send(mimeMessage);
	}
	
	private String renderMail(String mjml, Locale locale){
		var configuration = new Mjml4j.Configuration(locale.getLanguage());
		var rendered = Mjml4j.render(mjml, configuration);
		return htmlCompressor.compress(rendered);
	}
	
	@NonNull
	private Optional<byte[]> getSeerrLogoBytes(){
		return getResourceBytes("static/seerr.png");
	}
	
	@NonNull
	private Optional<byte[]> getPlexLogoBytes(){
		return getResourceBytes("static/plex.png");
	}
	
	@NonNull
	private Optional<byte[]> getTmdbLogoBytes(){
		return getResourceBytes("static/tmdb.png");
	}
	
	@NonNull
	private Optional<byte[]> getTvdbLogoBytes(){
		return getResourceBytes("static/tvdb.png");
	}
	
	@NonNull
	private Optional<byte[]> getTraktLogoBytes(){
		return getResourceBytes("static/trakt.png");
	}
	
	@NonNull
	private Optional<byte[]> getResourceBytes(@Nullable String path){
		if(Objects.isNull(path)){
			return Optional.empty();
		}
		try{
			var classPathResource = new ClassPathResource(path);
			if(!classPathResource.exists()){
				log.warn("Failed to get resource {}, does not exists", path);
				return Optional.empty();
			}
			return Optional.of(classPathResource.getContentAsByteArray());
		}
		catch(Exception e){
			log.error("Failed to get resource {}", path, e);
			return Optional.empty();
		}
	}
	
	@SafeVarargs
	private boolean hasAnyMediaValueNotNull(Function<MediaEntity, Object> propertyExtractor, List<MediaEntity>... medias){
		return Arrays.stream(medias)
				.flatMap(Collection::stream)
				.map(propertyExtractor)
				.anyMatch(Objects::nonNull);
	}
	
	private interface MessageFiller{
		void accept(MimeMessageHelper mimeMessageHelper) throws MessagingException, UnsupportedEncodingException;
	}
	
	private interface ContextFiller{
		void accept(Context context) throws MessagingException, UnsupportedEncodingException;
	}
}
