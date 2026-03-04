package fr.rakambda.plexdeleter.notify;

import fr.rakambda.plexdeleter.api.RequestFailedException;
import fr.rakambda.plexdeleter.api.discord.DiscordWebhookApiService;
import fr.rakambda.plexdeleter.api.discord.data.Attachment;
import fr.rakambda.plexdeleter.api.discord.data.Embed;
import fr.rakambda.plexdeleter.api.discord.data.Field;
import fr.rakambda.plexdeleter.api.discord.data.Image;
import fr.rakambda.plexdeleter.api.discord.data.WebhookMessage;
import fr.rakambda.plexdeleter.api.tautulli.data.AudioMediaPartStream;
import fr.rakambda.plexdeleter.api.tautulli.data.MediaInfo;
import fr.rakambda.plexdeleter.api.tautulli.data.SubtitlesMediaPartStream;
import fr.rakambda.plexdeleter.notify.context.MediaMetadataContext;
import fr.rakambda.plexdeleter.service.ThymeleafService;
import fr.rakambda.plexdeleter.service.WatchService;
import fr.rakambda.plexdeleter.storage.entity.MediaEntity;
import fr.rakambda.plexdeleter.storage.entity.MediaRequirementEntity;
import fr.rakambda.plexdeleter.storage.entity.NotificationEntity;
import fr.rakambda.plexdeleter.storage.entity.NotificationType;
import fr.rakambda.plexdeleter.storage.entity.UserGroupEntity;
import jakarta.mail.MessagingException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DiscordNotificationService extends AbstractNotificationService{
	private static final int FLAG_SUPPRESS_EMBEDS = 1 << 2;
	
	private final DiscordWebhookApiService discordWebhookApiService;
	private final MessageSource messageSource;
	private final ThymeleafService thymeleafService;
	
	@Autowired
	public DiscordNotificationService(DiscordWebhookApiService discordWebhookApiService, MessageSource messageSource, WatchService watchService, ThymeleafService thymeleafService){
		super(watchService, messageSource);
		this.discordWebhookApiService = discordWebhookApiService;
		this.messageSource = messageSource;
		this.thymeleafService = thymeleafService;
	}
	
	public void notifyWatchlist(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull Collection<MediaRequirementEntity> requirements) throws InterruptedException, RequestFailedException{
		var locale = userGroupEntity.getLocaleAsObject();
		var params = notification.getValue().split(",");
		var discordUserId = params[0];
		var discordUrl = params[1];
		
		var availableMedia = requirements.stream()
				.map(MediaRequirementEntity::getMedia)
				.filter(m -> m.getStatus().isFullyDownloaded())
				.sorted(MediaEntity.COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX)
				.toList();
		var downloadingMedia = requirements.stream()
				.map(MediaRequirementEntity::getMedia)
				.filter(m -> m.getStatus().isDownloadStarted() && !m.getStatus().isFullyDownloaded())
				.sorted(MediaEntity.COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX)
				.toList();
		var notYetAvailableMedia = requirements.stream()
				.map(MediaRequirementEntity::getMedia)
				.filter(m -> !m.getStatus().isDownloadStarted())
				.sorted(MediaEntity.COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX)
				.toList();
		
		if(availableMedia.isEmpty() && downloadingMedia.isEmpty()){
			log.info("No medias eligible to notify");
			return;
		}
		
		var header = messageSource.getMessage("discord.watchlist.subject", new Object[0], locale);
		var threadId = switch(notification.getType()){
			case DISCORD_THREAD -> Optional.ofNullable(discordWebhookApiService.sendWebhookMessage(discordUrl, WebhookMessage.builder()
									.threadName(header)
									.content("<@%s>".formatted(discordUserId))
									.build())
							.getChannelId())
					.orElseThrow(() -> new RequestFailedException("Couldn't get new thread channel id"));
			default -> {
				discordWebhookApiService.sendWebhookMessage(discordUrl, WebhookMessage.builder()
						.content("<@%s>\n# %s".formatted(discordUserId, header))
						.build());
				yield null;
			}
		};
		
		if(!availableMedia.isEmpty()){
			writeWatchlistSection(discordUrl, threadId, "discord.watchlist.body.header.available", locale, userGroupEntity, availableMedia);
		}
		if(!downloadingMedia.isEmpty()){
			writeWatchlistSection(discordUrl, threadId, "discord.watchlist.body.header.downloading", locale, userGroupEntity, downloadingMedia);
		}
		if(!notYetAvailableMedia.isEmpty()){
			writeWatchlistSection(discordUrl, threadId, "discord.watchlist.body.header.not-yet-available", locale, userGroupEntity, notYetAvailableMedia);
		}
		discordWebhookApiService.sendWebhookMessage(discordUrl, threadId, WebhookMessage.builder()
				.content(getFooterContent(locale))
				.build());
	}
	
	public void notifyRequirementAdded(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media) throws MessagingException, UnsupportedEncodingException, RequestFailedException, InterruptedException{
		notifySimple(notification, userGroupEntity, media, "discord.requirement.added.subject", true);
	}
	
	public void notifyMediaAvailable(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media) throws RequestFailedException, InterruptedException{
		notifySimple(notification, userGroupEntity, media, "discord.media.available.subject", true);
	}
	
	public void notifyMediaDeleted(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media) throws RequestFailedException, InterruptedException{
		notifySimple(notification, userGroupEntity, media, "discord.media.deleted.subject", false);
	}
	
	public void notifyMediaWatched(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media) throws RequestFailedException, InterruptedException{
		notifySimple(notification, userGroupEntity, media, "discord.media.watched.subject", false);
	}
	
	public void notifyRequirementManuallyWatched(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media) throws MessagingException, UnsupportedEncodingException, RequestFailedException, InterruptedException{
		notifySimple(notification, userGroupEntity, media, "discord.requirement.manually-watched.subject", false);
	}
	
	public void notifyRequirementManuallyAbandoned(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media) throws RequestFailedException, InterruptedException{
		notifySimple(notification, userGroupEntity, media, "discord.requirement.manually-abandoned.subject", false);
	}
	
	public void notifyMediaAdded(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaMetadataContext mediaMetadataContext, @Nullable MediaEntity media) throws RequestFailedException, InterruptedException{
		var locale = userGroupEntity.getLocaleAsObject();
		var metadata = mediaMetadataContext.getMetadata();
		var params = notification.getValue().split(",");
		var discordUserId = params[0];
		var discordUrl = params[1];
		
		var context = new Context();
		context.setLocale(userGroupEntity.getLocaleAsObject());
		
		var mediaSeason = getMediaSeason(metadata, locale);
		var releaseDate = Optional.ofNullable(metadata.getOriginallyAvailableAt())
				.map(DATE_FORMATTER::format)
				.orElse(null);
		var audioLanguages = getMediaStreams(metadata, AudioMediaPartStream.class)
				.map(AudioMediaPartStream::getAudioLanguageCode)
				.distinct()
				.map(s -> Objects.equals(s, "") ? "unknown" : s)
				.map("locale.%s"::formatted)
				.map(key -> messageSource.getMessage(key, new Object[0], locale))
				.toList();
		var subtitleLanguages = getMediaStreams(metadata, SubtitlesMediaPartStream.class)
				.map(SubtitlesMediaPartStream::getSubtitleLanguageCode)
				.distinct()
				.map(s -> Objects.equals(s, "") ? "unknown" : s)
				.map("locale.%s"::formatted)
				.map(key -> messageSource.getMessage(key, new Object[0], locale))
				.toList();
		var resolutions = metadata.getMediaInfo().stream()
				.map(MediaInfo::getVideoFullResolution)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		var bitrates = metadata.getMediaInfo().stream()
				.map(MediaInfo::getBitrate)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		
		var metadataProvidersInfo = mediaMetadataContext.getMetadataProviderInfo();
		var requirements = Optional.ofNullable(media)
				.map(m -> m.getRequirements().stream()
						.filter(r -> Objects.equals(r.getGroup().getId(), userGroupEntity.getId()))
						.toList())
				.orElseGet(List::of);
		var ping = requirements.stream()
				.map(MediaRequirementEntity::getStatus)
				.anyMatch(r -> !r.isCompleted());
		
		var messageBuilder = WebhookMessage.builder();
		var embed = Embed.builder()
				.title(mediaMetadataContext.getTitle(locale).orElseGet(metadata::getFullTitle))
				.description(mediaSeason);
		
		mediaMetadataContext.getPosterData().ifPresent(poster -> {
			embed.image(Image.builder()
					.url("attachment://poster.jpg")
					.build());
			
			messageBuilder.attachment(Attachment.builder()
					.id(0)
					.description("Poster image")
					.filename("poster.jpg")
					.data(poster)
					.mediaType(MediaType.IMAGE_JPEG)
					.build());
		});
		
		if(requirements.isEmpty()){
			Optional.ofNullable(media)
					.map(MediaEntity::getId)
					.ifPresent(id -> embed.field(Field.builder()
							.name(messageSource.getMessage("discord.media.available.body.actions", new Object[0], locale))
							.value("[%s](<%s>)".formatted(
									messageSource.getMessage("discord.media.available.body.actions.requirement.add", new Object[0], locale),
									thymeleafService.getAddWatchMediaUrl(id)
							))
							.build()));
		}
		
		Optional.ofNullable(mediaMetadataContext.getSummary(locale).orElseGet(metadata::getSummary))
				.filter(s -> !s.isBlank())
				.ifPresent(s -> embed.field(Field.builder()
						.name(messageSource.getMessage("discord.media.available.body.summary", new Object[0], locale))
						.value(s)
						.build()));
		Optional.ofNullable(releaseDate)
				.ifPresent(s -> embed.field(Field.builder()
						.name(messageSource.getMessage("discord.media.available.body.release-date", new Object[0], locale))
						.value(s)
						.build()));
		if(!metadata.getActors().isEmpty()){
			embed.field(Field.builder()
					.name(messageSource.getMessage("discord.media.available.body.actors", new Object[0], locale))
					.value(metadata.getActors().stream().limit(5).collect(Collectors.joining(", ")))
					.build());
		}
		var genres = mediaMetadataContext.getGenres(messageSource, locale).orElseGet(metadata::getGenres);
		if(!genres.isEmpty()){
			embed.field(Field.builder()
					.name(messageSource.getMessage("discord.media.available.body.genres", new Object[0], locale))
					.value(String.join(", ", genres))
					.build());
		}
		Optional.ofNullable(metadata.getDuration())
				.map(Duration::ofMillis)
				.map(this::getMediaDuration)
				.ifPresent(duration -> embed.field(Field.builder()
						.name(messageSource.getMessage("discord.media.available.body.length", new Object[0], locale))
						.value(duration)
						.build()));
		if(!audioLanguages.isEmpty()){
			embed.field(Field.builder()
					.name(messageSource.getMessage("discord.media.available.body.audios", new Object[0], locale))
					.value(String.join(", ", audioLanguages))
					.build());
		}
		if(!subtitleLanguages.isEmpty()){
			embed.field(Field.builder()
					.name(messageSource.getMessage("discord.media.available.body.subtitles", new Object[0], locale))
					.value(String.join(", ", subtitleLanguages))
					.build());
		}
		if(!resolutions.isEmpty()){
			embed.field(Field.builder()
					.name(messageSource.getMessage("discord.media.available.body.resolutions", new Object[0], locale))
					.value(String.join(", ", resolutions))
					.build());
		}
		if(!bitrates.isEmpty()){
			embed.field(Field.builder()
					.name(messageSource.getMessage("discord.media.available.body.bitrates", new Object[0], locale))
					.value(bitrates.stream().map(String::valueOf).collect(Collectors.joining(", ")))
					.build());
		}
		if(!metadataProvidersInfo.isEmpty()){
			embed.field(Field.builder()
					.name(messageSource.getMessage("discord.media.available.body.external.links", new Object[0], locale))
					.value(metadataProvidersInfo.stream()
							.map(c -> "[%s](<%s>)".formatted(c.name(), c.url()))
							.collect(Collectors.joining(" ")))
					.build());
		}
		
		messageBuilder.embeds(List.of(embed.build()));
		
		if(ping){
			messageBuilder.content("<@%s>".formatted(discordUserId));
		}
		
		var header = messageSource.getMessage("discord.media.added.subject", new Object[0], locale);
		if(notification.getType() == NotificationType.DISCORD_THREAD){
			messageBuilder.threadName(header);
		}
		else{
			messageBuilder.content("# %s".formatted(header));
		}
		
		discordWebhookApiService.sendWebhookMessage(discordUrl, messageBuilder.build());
	}
	
	private void notifySimple(@NonNull NotificationEntity notification, @NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @NonNull String subjectKey, boolean includeEpisodes) throws RequestFailedException, InterruptedException{
		var locale = userGroupEntity.getLocaleAsObject();
		var params = notification.getValue().split(",");
		var discordUserId = params[0];
		var discordUrl = params[1];
		
		var messageBuilder = WebhookMessage.builder();
		
		var header = messageSource.getMessage(subjectKey, new Object[0], locale);
		if(notification.getType() == NotificationType.DISCORD_THREAD){
			messageBuilder = messageBuilder
					.threadName(header)
					.content("<@%s>\n%s\n\n%s".formatted(
							discordUserId,
							getWatchlistMediaText(userGroupEntity, media, locale, includeEpisodes),
							getFooterContent(locale)
					));
		}
		else{
			messageBuilder = messageBuilder
					.content("<@%s>\n# %s\n%s\n\n%s".formatted(
							discordUserId,
							header,
							getWatchlistMediaText(userGroupEntity, media, locale, includeEpisodes),
							getFooterContent(locale)
					));
		}
		
		discordWebhookApiService.sendWebhookMessage(discordUrl, messageBuilder.build());
	}
	
	private String getFooterContent(Locale locale){
		return "[%s](<%s>)".formatted(messageSource.getMessage("mail.footer.app-link", new Object[0], locale), thymeleafService.getOwnUrl());
	}
	
	private void writeWatchlistSection(@NonNull String discordUrl, @Nullable Long threadId, @NonNull String sectionHeaderCode, @NonNull Locale locale, @NonNull UserGroupEntity userGroupEntity, @NonNull Collection<MediaEntity> medias) throws RequestFailedException, InterruptedException{
		discordWebhookApiService.sendWebhookMessage(discordUrl, threadId, WebhookMessage.builder().content("# %s\n".formatted(messageSource.getMessage(sectionHeaderCode, new Object[0], locale))).build());
		var messages = medias.stream()
				.sorted(MediaEntity.COMPARATOR_BY_TYPE_THEN_NAME_THEN_INDEX)
				.map(media -> getWatchlistMediaText(userGroupEntity, media, locale, true))
				.toList();
		for(var message : messages){
			discordWebhookApiService.sendWebhookMessage(discordUrl, threadId, WebhookMessage.builder()
					.content("* %s".formatted(message))
					.flags(FLAG_SUPPRESS_EMBEDS)
					.build());
		}
	}
	
	@SneakyThrows(RequestFailedException.class)
	private String getWatchlistMediaText(@NonNull UserGroupEntity userGroupEntity, @NonNull MediaEntity media, @NonNull Locale locale, boolean includeEpisodes){
		var sb = new StringBuilder();
		sb.append(messageSource.getMessage(getTypeKey(media), new Object[]{
				media.getName(),
				media.getIndex(),
				}, locale));
		
		if(includeEpisodes){
			var episodes = getEpisodes(media, userGroupEntity);
			if(!episodes.isEmpty()){
				sb.append(" | ");
				sb.append(messageSource.getMessage("discord.watchlist.body.media.series.episodes", new Object[]{String.join(", ", episodes)}, locale));
			}
		}
		
		var seerrUrl = thymeleafService.getMediaSeerrUrl(media);
		if(Objects.nonNull(seerrUrl)){
			sb.append(" | ");
			sb.append("[Seerr](");
			sb.append(seerrUrl);
			sb.append(")");
		}
		
		var plexUrl = thymeleafService.getMediaPlexUrl(media);
		if(Objects.nonNull(plexUrl)){
			sb.append(" | ");
			sb.append("[Plex](");
			sb.append(plexUrl);
			sb.append(")");
		}
		
		var tmdbUrl = thymeleafService.getMediaTmdbUrl(media);
		if(Objects.nonNull(tmdbUrl)){
			sb.append(" | ");
			sb.append("[Tmdb](");
			sb.append(tmdbUrl);
			sb.append(")");
		}
		
		var tvdbUrl = thymeleafService.getMediaTvdbUrl(media);
		if(Objects.nonNull(tvdbUrl)){
			sb.append(" | ");
			sb.append("[Tvdb](");
			sb.append(tvdbUrl);
			sb.append(")");
		}
		
		var traktUrl = thymeleafService.getMediaTraktUrl(media);
		if(Objects.nonNull(traktUrl)){
			sb.append(" | ");
			sb.append("[Trakt](");
			sb.append(traktUrl);
			sb.append(")");
		}
		
		return sb.toString();
	}
}
