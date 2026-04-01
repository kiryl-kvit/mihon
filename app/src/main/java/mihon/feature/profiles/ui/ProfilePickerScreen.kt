package mihon.feature.profiles.ui

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import mihon.feature.profiles.core.Profile
import mihon.feature.profiles.core.ProfileManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ProfilePickerScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val profileManager = remember { Injekt.get<ProfileManager>() }
        val uiPreferences = remember { Injekt.get<UiPreferences>() }

        val profiles by profileManager.visibleProfiles.collectAsState()
        val activeProfile by profileManager.activeProfile.collectAsState()

        ProfilePickerScene(
            profiles = profiles,
            activeProfileId = activeProfile?.id,
            onProfileSelected = { profile ->
                scope.launch {
                    val switched = switchToProfile(
                        context = context,
                        profileManager = profileManager,
                        uiPreferences = uiPreferences,
                        profile = profile,
                    )
                    if (switched) {
                        navigator.pop()
                    }
                }
            },
            onOpenManagement = null,
        )
    }
}

@Composable
fun ProfilePickerScene(
    profiles: List<Profile>,
    activeProfileId: Long?,
    onProfileSelected: (Profile) -> Unit,
    onOpenManagement: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(context, view) {
        val activity = context as? Activity
        if (activity == null) {
            onDispose {}
        } else {
            val controller = WindowInsetsControllerCompat(activity.window, view)
            val previousStatusBars = controller.isAppearanceLightStatusBars
            val previousNavigationBars = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false

            onDispose {
                controller.isAppearanceLightStatusBars = previousStatusBars
                controller.isAppearanceLightNavigationBars = previousNavigationBars
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
            ) {
                if (onOpenManagement != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenManagement)
                            .padding(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(MR.strings.profiles_title),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Icon(
                painter = painterResource(R.drawable.ic_mihon),
                contentDescription = stringResource(MR.strings.app_name),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = stringResource(MR.strings.profiles_choose_profile),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 44.dp, bottom = 28.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    maxItemsInEachRow = 3,
                ) {
                    profiles.forEach { profile ->
                        ProfilePickerTile(
                            profile = profile,
                            isActive = profile.id == activeProfileId,
                            onClick = { onProfileSelected(profile) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePickerTile(
    profile: Profile,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val faceColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.width(ProfileTileSize),
    ) {
        Box(
            modifier = Modifier
                .size(ProfileTileSize)
                .clip(RoundedCornerShape(12.dp))
                .background(profileTileBrush(profile.colorSeed))
                .border(
                    width = if (isActive) 2.dp else 1.dp,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onClick),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.06f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.16f),
                            ),
                        ),
                    ),
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
            ) {
                val eyeRadius = size.minDimension * 0.05f
                drawCircle(faceColor, eyeRadius, Offset(size.width * 0.34f, size.height * 0.34f))
                drawCircle(faceColor, eyeRadius, Offset(size.width * 0.66f, size.height * 0.34f))
                drawArc(
                    color = faceColor,
                    startAngle = 18f,
                    sweepAngle = 144f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.38f),
                    size = Size(size.width * 0.44f, size.height * 0.28f),
                    style = Stroke(width = size.minDimension * 0.045f, cap = StrokeCap.Round),
                )
            }
        }

        Text(
            text = profile.name,
            color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun profileTileBrush(seed: Long): Brush {
    val hue = ((seed % 360L) + 360L) % 360L
    return Brush.linearGradient(
        colors = listOf(
            Color.hsv(hue.toFloat(), 0.54f, 0.88f),
            Color.hsv(((hue + 42L) % 360L).toFloat(), 0.6f, 0.72f),
        ),
    )
}

private val ProfileTileSize = 118.dp
