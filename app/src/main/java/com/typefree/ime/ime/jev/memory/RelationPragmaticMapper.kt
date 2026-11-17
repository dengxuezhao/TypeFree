package com.typefree.ime.ime.jev.memory

import com.typefree.ime.ime.jev.ChatTopology
import com.typefree.ime.ime.jev.JevRoleMode

object RelationPragmaticMapper {

    const val GLOBAL_BUCKET_KEY = "GLOBAL"

    fun toBucketKey(
        topologyKind: TopologyKind,
        privateTag: PrivateRelationTag,
        groupTag: GroupSceneTag
    ): String? {
        return when (topologyKind) {
            TopologyKind.PRIV -> if (privateTag != PrivateRelationTag.UNSET) {
                "PRIV::${privateTag.code}"
            } else {
                null
            }
            TopologyKind.GRP -> if (groupTag != GroupSceneTag.UNSET) {
                "GRP::${groupTag.code}"
            } else {
                null
            }
        }
    }

    fun resolveActiveAxisGroup(
        userBottomLine: String,
        routeCode: String?,
        roleMode: JevRoleMode
    ): JevAxisGroup {
        if (userBottomLine.isNotBlank()) return JevAxisGroup.BOTTOM_LINE_LOCKED
        JevAxisGroup.fromRouteCode(routeCode)?.let { return it }
        return if (roleMode == JevRoleMode.GROUP_BYSTANDER) {
            JevAxisGroup.GROUP_BYSTANDER
        } else {
            JevAxisGroup.TKI_GAME
        }
    }

    fun resolveDominantAxisHint(
        axisGroup: JevAxisGroup,
        sessionStats: AxisPreferenceStats?,
        bucketStats: AxisPreferenceStats?,
        nowMs: Long
    ): String? {
        val decayedSess = JevPreferenceLearner.applyTemporalDecayToAxis(sessionStats, nowMs)
        val decayedBucket = JevPreferenceLearner.applyTemporalDecayToAxis(bucketStats, nowMs)
        val chosen = when {
            decayedSess != null && decayedSess.sampleCount >= 6 -> decayedSess
            decayedBucket != null && decayedBucket.sampleCount >= 6 -> decayedBucket
            else -> return null
        }
        val maxIdx = chosen.probs.indices.maxByOrNull { chosen.probs[it] } ?: return null
        if (chosen.probs[maxIdx] < 0.55) return null
        val axisLabel = axisGroup.axisLabels.getOrNull(maxIdx) ?: return null
        return "用户在当前语境下常偏爱「$axisLabel」方向，可在保持三策差异化前提下适度贴合语感"
    }

    /**
     * Resolves deterministic style rules using strict hierarchy:
     * `User Manual Override > Session Active Rules > Relation Bucket Active Rules > Global Active Rules`
     * while enforcing mutual exclusion between `HONORIFIC_NIN_TO_NI` and `HONORIFIC_NI_TO_NIN`.
     */
    fun resolveEffectiveStyleRules(
        session: SessionMemoryRecord?,
        bucketProfile: BucketStyleProfile?,
        globalProfile: BucketStyleProfile?,
        ephemeralPrivateTag: PrivateRelationTag = PrivateRelationTag.UNSET
    ): Pair<List<String>, Boolean> {
        val resolvedCounters = LinkedHashMap<StyleFeatureKey, FeatureCounter>()

        for (key in StyleFeatureKey.entries) {
            val sessCounter = session?.featureCounters?.get(key)
            val bucketCounter = bucketProfile?.featureCounters?.get(key)
            val globalCounter = if (key.allowGlobalScope) globalProfile?.featureCounters?.get(key) else null

            val overrideCounter = listOfNotNull(sessCounter, bucketCounter, globalCounter)
                .firstOrNull { it.userOverride != null }
            if (overrideCounter != null) {
                if (overrideCounter.userOverride == true) {
                    resolvedCounters[key] = overrideCounter
                }
                continue
            }

            val winningActive = when {
                sessCounter?.isActive == true -> sessCounter
                bucketCounter?.isActive == true -> bucketCounter
                globalCounter?.isActive == true -> globalCounter
                else -> null
            }
            if (winningActive != null) {
                resolvedCounters[key] = winningActive
            }
        }

        // Guard honorific mutual exclusion & protect superiors/elders from accidental bucket leaks
        val effectivePrivateTag = session?.privateTag?.takeIf { it != PrivateRelationTag.UNSET }
            ?: ephemeralPrivateTag
        if (effectivePrivateTag == PrivateRelationTag.SUPERIOR_ELDER &&
            session?.featureCounters?.get(StyleFeatureKey.HONORIFIC_NIN_TO_NI)?.effectiveActive != true
        ) {
            resolvedCounters.remove(StyleFeatureKey.HONORIFIC_NIN_TO_NI)
        }
        if (resolvedCounters.containsKey(StyleFeatureKey.HONORIFIC_NIN_TO_NI) &&
            resolvedCounters.containsKey(StyleFeatureKey.HONORIFIC_NI_TO_NIN)
        ) {
            val sessNin = session?.featureCounters?.get(StyleFeatureKey.HONORIFIC_NIN_TO_NI)?.effectiveActive == true
            val sessNiToNin = session?.featureCounters?.get(StyleFeatureKey.HONORIFIC_NI_TO_NIN)?.effectiveActive == true
            if (sessNin && !sessNiToNin) {
                resolvedCounters.remove(StyleFeatureKey.HONORIFIC_NI_TO_NIN)
            } else {
                resolvedCounters.remove(StyleFeatureKey.HONORIFIC_NIN_TO_NI)
            }
        }

        val rules = resolvedCounters.entries
            .take(5)
            .map { (key, counter) -> JevPreferenceLearner.formatRuleText(key, counter) }
        val hasLengthCap = resolvedCounters.containsKey(StyleFeatureKey.COMPRESS_LENGTH)
        return rules to hasLengthCap
    }

    fun buildPromptPayload(
        topology: ChatTopology,
        anchor: AnchorResolution?,
        session: SessionMemoryRecord?,
        ephemeralPrivateTag: PrivateRelationTag,
        ephemeralGroupTag: GroupSceneTag,
        ephemeralCustomNote: String?,
        bucketProfile: BucketStyleProfile?,
        globalProfile: BucketStyleProfile?,
        nowMs: Long
    ): JevMemoryPromptPayload {
        val effectivePriv = session?.privateTag?.takeIf { it != PrivateRelationTag.UNSET }
            ?: ephemeralPrivateTag
        val effectiveGrp = session?.groupTag?.takeIf { it != GroupSceneTag.UNSET }
            ?: ephemeralGroupTag
        val effectiveNote = session?.redactedCustomNote?.takeIf { it.isNotBlank() }
            ?: ephemeralCustomNote?.takeIf { it.isNotBlank() }

        val (relationLabel, guideline) = if (topology.isGroupChat) {
            if (effectiveGrp != GroupSceneTag.UNSET) {
                effectiveGrp.shortLabel to effectiveGrp.guideline
            } else {
                null to null
            }
        } else {
            if (effectivePriv != PrivateRelationTag.UNSET) {
                effectivePriv.shortLabel to effectivePriv.guideline
            } else {
                null to null
            }
        }

        val externalHint = if ((anchor as? AnchorResolution.Resolved)?.hasExternalBadge == true) {
            "含[外部]协作标识，需注意跨组织说话口径"
        } else {
            null
        }

        val (rules, hasLengthCap) = resolveEffectiveStyleRules(
            session = session,
            bucketProfile = bucketProfile,
            globalProfile = globalProfile,
            ephemeralPrivateTag = effectivePriv
        )

        val defaultAxisGroup = if (topology.roleMode == JevRoleMode.GROUP_BYSTANDER) {
            JevAxisGroup.GROUP_BYSTANDER
        } else {
            JevAxisGroup.TKI_GAME
        }
        val tendencyHint = resolveDominantAxisHint(
            axisGroup = defaultAxisGroup,
            sessionStats = session?.axisStats?.get(defaultAxisGroup),
            bucketStats = bucketProfile?.axisStats?.get(defaultAxisGroup),
            nowMs = nowMs
        )

        return JevMemoryPromptPayload(
            relationLabel = relationLabel,
            guideline = guideline,
            externalBoundaryHint = externalHint,
            redactedCustomNote = effectiveNote,
            deterministicStyleRules = rules,
            axisTendencyHint = tendencyHint,
            includesLengthCapRule = hasLengthCap
        )
    }
}
