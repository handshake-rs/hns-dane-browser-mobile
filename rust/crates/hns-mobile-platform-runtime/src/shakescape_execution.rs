//! Shared, bounded swap-observation workflow for the two native wallet shells.
//!
//! Platform adapters lend one controller at a time. In particular, Bitcoin
//! verification never runs while the HNS/session controller lock is held.

use hns_wallet_mobile::{
    EmbeddedHnsBackend, HnsClock, HnsReadSystemClock, MobileBitcoinValueController,
    MobileHnsValueController, MobileShakescapeBitcoinFundingPermit,
    MobileShakescapeBitcoinSettlementPermit, MobileShakescapeFirstFundingObservation,
    MobileShakescapeHnsFundingPermit, MobileShakescapeHnsSettlementPermit,
    MobileShakescapeSessionController, MobileShakescapeSettlementAction, MobileWalletError,
    ReconciledShakescapeBitcoinFunding, VerifiedBitcoinHtlcSpendObservation,
};
use hns_wallet_types::{ModuleId, SessionId};

/// Device-specific locking and error presentation. A closure holds its borrowed
/// controller only for that call; the shared workflow decides the order and
/// applies verified observations through the wallet crate's authority gates.
pub trait ExecutionAccess {
    type Error;

    fn with_sessions<T>(
        &self,
        operation: impl FnOnce(&mut MobileShakescapeSessionController) -> T,
    ) -> Result<T, Self::Error>;

    fn with_hns<T>(
        &self,
        operation: impl FnOnce(
            &MobileHnsValueController<EmbeddedHnsBackend>,
            &mut MobileShakescapeSessionController,
        ) -> T,
    ) -> Result<T, Self::Error>;

    fn with_bitcoin<T>(
        &self,
        required: bool,
        operation: impl FnOnce(Option<&MobileBitcoinValueController>) -> T,
    ) -> Result<T, Self::Error>;

    fn wallet_error(&self, context: &'static str, error: MobileWalletError) -> Self::Error;
}

fn now_unix() -> Result<u64, MobileWalletError> {
    Ok(HnsReadSystemClock.now_unix()?)
}

/// A first-chain HNS lock needs only its owner's approval. The second-chain
/// lock requires a fresh, checkpoint-bound Bitcoin observation; a verified
/// absence revokes the prior first-funding readiness before returning an error.
pub fn authorize_hns_funding(
    sessions: &mut MobileShakescapeSessionController,
    session_id: SessionId,
    first_bitcoin_funding: Option<(
        MobileShakescapeFirstFundingObservation,
        ReconciledShakescapeBitcoinFunding,
    )>,
) -> Result<MobileShakescapeHnsFundingPermit, MobileWalletError> {
    let now = now_unix()?;
    if let Ok(permit) = sessions.authorize_local_hns_first_funding(session_id, now) {
        return Ok(permit);
    }
    match first_bitcoin_funding {
        Some((permit, ReconciledShakescapeBitcoinFunding::Confirmed(lock))) => {
            sessions.authorize_local_hns_second_funding(session_id, permit, lock, now)
        }
        Some((permit, ReconciledShakescapeBitcoinFunding::NotConfirmed)) => {
            sessions.reconcile_local_bitcoin_funding(
                session_id,
                permit,
                ReconciledShakescapeBitcoinFunding::NotConfirmed,
                now,
            )?;
            Err(MobileWalletError::InvalidShakescapeSessionMessage)
        }
        None => Err(MobileWalletError::InvalidShakescapeSessionMessage),
    }
}

/// A second Bitcoin lock requires currently verified HNS funding. An absent
/// first-chain lock is reconciled before the approval is denied.
pub fn authorize_bitcoin_funding(
    hns: &MobileHnsValueController<EmbeddedHnsBackend>,
    sessions: &mut MobileShakescapeSessionController,
    session_id: SessionId,
) -> Result<MobileShakescapeBitcoinFundingPermit, MobileWalletError> {
    let now = now_unix()?;
    if let Ok(permit) = sessions.authorize_local_btc_first_funding(session_id, now) {
        return Ok(permit);
    }
    let verification = sessions.hns_funding_verification_permit(session_id)?;
    let observation = sessions.begin_local_hns_first_funding_observation(session_id, now)?;
    let verified = hns.verified_shakescape_hns_funding(verification)?;
    let observation_now = now_unix()?;
    match verified {
        Some(lock) => sessions.authorize_local_btc_second_funding(
            session_id,
            observation,
            lock,
            observation_now,
        ),
        None => {
            sessions.reconcile_local_hns_funding(session_id, observation, None, observation_now)?;
            Err(MobileWalletError::InvalidShakescapeSessionMessage)
        }
    }
}

pub fn authorize_hns_settlement(
    sessions: &MobileShakescapeSessionController,
    session_id: SessionId,
    action: MobileShakescapeSettlementAction,
) -> Result<MobileShakescapeHnsSettlementPermit, MobileWalletError> {
    match action {
        MobileShakescapeSettlementAction::Redeem => sessions.authorize_local_hns_redeem(session_id),
        MobileShakescapeSettlementAction::Refund => sessions.authorize_local_hns_refund(session_id),
    }
}

pub fn authorize_bitcoin_settlement(
    sessions: &MobileShakescapeSessionController,
    session_id: SessionId,
    redeem: bool,
) -> Result<MobileShakescapeBitcoinSettlementPermit, MobileWalletError> {
    if redeem {
        sessions.authorize_local_bitcoin_redeem(session_id)
    } else {
        sessions.authorize_local_bitcoin_refund(session_id)
    }
}

fn reconcile_bitcoin_funding(
    sessions: &mut MobileShakescapeSessionController,
    session_id: SessionId,
    permit: Option<MobileShakescapeFirstFundingObservation>,
    observation: ReconciledShakescapeBitcoinFunding,
) -> Result<(), MobileWalletError> {
    let now = now_unix()?;
    match permit {
        Some(permit) => {
            sessions.reconcile_local_bitcoin_funding(session_id, permit, observation, now)?;
        }
        None => match observation {
            ReconciledShakescapeBitcoinFunding::Confirmed(lock) => {
                sessions.apply_local_verified_bitcoin_funding(session_id, lock, now)?;
            }
            ReconciledShakescapeBitcoinFunding::NotConfirmed => {}
        },
    }
    Ok(())
}

fn reconcile_bitcoin_spend(
    sessions: &mut MobileShakescapeSessionController,
    session_id: SessionId,
    spend: Option<VerifiedBitcoinHtlcSpendObservation>,
) -> Result<(), MobileWalletError> {
    let now = now_unix()?;
    match spend {
        Some(spend) => {
            sessions.apply_local_verified_bitcoin_spend(session_id, spend, now)?;
        }
        None => {
            sessions.invalidate_recovered_spend_observation(session_id, ModuleId::Bitcoin, now)?
        }
    }
    Ok(())
}

fn reconcile_hns_funding(
    hns: &MobileHnsValueController<EmbeddedHnsBackend>,
    sessions: &mut MobileShakescapeSessionController,
) -> Result<(), MobileWalletError> {
    for permit in sessions.pending_second_hns_funding_verifications()? {
        let session_id = permit.session_id();
        let observation =
            sessions.maybe_begin_local_hns_first_funding_observation(session_id, now_unix()?)?;
        let verified = hns.verified_shakescape_hns_funding(permit)?;
        let now = now_unix()?;
        match (observation, verified) {
            (Some(observation), verified) => {
                sessions.reconcile_local_hns_funding(session_id, observation, verified, now)?;
            }
            (None, Some(lock)) => {
                sessions.apply_local_verified_hns_funding(session_id, lock, now)?;
            }
            (None, None) => {}
        }
    }
    Ok(())
}

fn reconcile_hns_spends(
    hns: &MobileHnsValueController<EmbeddedHnsBackend>,
    sessions: &mut MobileShakescapeSessionController,
) -> Result<(), MobileWalletError> {
    let mut first_error = None;
    for permit in sessions.pending_hns_spend_verifications()? {
        let session_id = permit.session_id();
        let result = match hns.verified_shakescape_hns_spend(permit) {
            Ok(Some(spend)) => sessions
                .apply_local_verified_hns_spend(session_id, spend, now_unix()?)
                .map(|_| ()),
            Ok(None) => sessions.invalidate_recovered_spend_observation(
                session_id,
                ModuleId::Handshake,
                now_unix()?,
            ),
            // A failed read cannot establish a spend or its absence. Continue
            // checking other sessions, then report the first failure.
            Err(error) => Err(error),
        };
        if let Err(error) = result
            && first_error.is_none()
        {
            first_error = Some(error);
        }
    }
    first_error.map_or(Ok(()), Err)
}

/// Advance both chains' confirmed funding and spend evidence. The HNS passes
/// are best-effort so a temporary HNS read error cannot hide durable status.
pub fn reconcile_observations<A: ExecutionAccess>(access: &A) -> Result<(), A::Error> {
    let candidates = access
        .with_sessions(|sessions| {
            sessions
                .pending_first_bitcoin_funding_sessions()?
                .into_iter()
                .map(|id| {
                    Ok((
                        id,
                        sessions
                            .maybe_begin_local_bitcoin_first_funding_observation(id, now_unix()?)?,
                    ))
                })
                .collect::<Result<Vec<_>, MobileWalletError>>()
        })?
        .map_err(|error| access.wallet_error("Bitcoin funding observation setup failed", error))?;
    if !candidates.is_empty() {
        let observed = access.with_bitcoin(true, |bitcoin| {
            bitcoin.map_or_else(Vec::new, |bitcoin| {
                candidates
                    .into_iter()
                    .filter_map(|(id, permit)| {
                        bitcoin
                            .reconciled_shakescape_htlc_funding(id)
                            .ok()
                            .flatten()
                            .map(|observation| (id, permit, observation))
                    })
                    .collect::<Vec<_>>()
            })
        })?;
        if !observed.is_empty() {
            access
                .with_sessions(|sessions| {
                    for (id, permit, observation) in observed {
                        reconcile_bitcoin_funding(sessions, id, permit, observation)?;
                    }
                    Ok::<_, MobileWalletError>(())
                })?
                .map_err(|error| {
                    access.wallet_error("Bitcoin funding reconciliation failed", error)
                })?;
        }
    }

    let spend_candidates = access
        .with_sessions(|sessions| sessions.pending_bitcoin_spend_sessions())?
        .map_err(|error| access.wallet_error("Bitcoin spend recovery failed", error))?;
    if !spend_candidates.is_empty() {
        let observed = access.with_bitcoin(true, |bitcoin| {
            bitcoin.map_or_else(Vec::new, |bitcoin| {
                spend_candidates
                    .into_iter()
                    .filter_map(|id| {
                        bitcoin
                            .verified_shakescape_htlc_spend(id)
                            .ok()
                            .map(|spend| (id, spend))
                    })
                    .collect::<Vec<_>>()
            })
        })?;
        if !observed.is_empty() {
            access
                .with_sessions(|sessions| {
                    for (id, spend) in observed {
                        reconcile_bitcoin_spend(sessions, id, spend)?;
                    }
                    Ok::<_, MobileWalletError>(())
                })?
                .map_err(|error| access.wallet_error("Bitcoin spend recovery failed", error))?;
        }
    }

    // Retain separate passes: one session's HNS funding read must not prevent
    // a confirmed spend on another session from being observed this round.
    if let Err(error) = access.with_hns(reconcile_hns_funding)? {
        let _ = access.wallet_error("HNS funding reconciliation failed", error);
    }
    if let Err(error) = access.with_hns(reconcile_hns_spends)? {
        let _ = access.wallet_error("HNS spend reconciliation failed", error);
    }
    Ok(())
}

/// Build the shared execution projection. The caller publishes the result
/// under its platform-specific snapshot lock; status reads use only that slot.
pub fn publish_execution_snapshot<A: ExecutionAccess, T>(
    access: &A,
    publish: impl FnOnce(serde_json::Value) -> Result<T, A::Error>,
) -> Result<T, A::Error> {
    let bitcoin_broadcast_recovery = access.with_bitcoin(false, |bitcoin| {
        bitcoin.and_then(|bitcoin| bitcoin.approved_broadcast_recovery().ok())
    })?;
    access
        .with_sessions(|sessions| {
            let now = now_unix()?;
            let executions = sessions.durable_executions()?;
            let pending_acceptances = sessions.pending_direct_offer_acceptances(now)?;
            let pending_offer_responses = sessions.pending_local_offer_responses(now)?;
            let projection = serde_json::json!({
                "executions": executions,
                "pendingAcceptances": pending_acceptances,
                "pendingOfferResponses": pending_offer_responses,
                "bitcoinBroadcastRecovery": bitcoin_broadcast_recovery,
            });
            Ok::<_, MobileWalletError>(publish(projection))
        })?
        .map_err(|error| access.wallet_error("Shakescape execution listing failed", error))?
}
