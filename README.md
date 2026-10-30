# TCDRM v2 — simulation CloudSim 7.0.1

Reproduction de la stratégie TCDRM (Bernardin et al., JLISS 12(3), 2025) et du
Sujet 2 (TCDRM-Predictive : P1 → P3 → P2) sur CloudSim 7.0.1.

| Scénario | Contenu |
| --- | --- |
| S1 | Scénario de l'article : TCDRM (algorithmes 1-2-3) contre NoRepLc |
| S2 | Même scénario, algorithme 2 remplacé par P1 → P3 → P2 |
| S3 | Scénario de l'article (comme S2) aux prix publics des fournisseurs (clés `real.*`), CSLA × 7 ; comparaison avec la Table 1 (S2) et avec le CSLA de la Table 1 |
| S4 | Comme S3 (1 000 requêtes, origine au hasard, prix publics) mais requêtes tirées de 5 modèles aléatoires par graine (une relation partagée, une commune, des relations fréquentes et rares ; un modèle rare avec une rafale) ; périodes de 50 requêtes (l'algorithme 3 supprime), écritures, budget par cycle ; ablation P2 / P1+P2 / P3+P2 ; sensibilité au taux d'écriture ; validation de P1 et variantes v2 |
| S5 | S4 + variation des prix (période 20) + charge non stationnaire : 2 000 requêtes, le classement des modèles fréquents tourne d'un rang toutes les 5 périodes (motif de 20 périodes) ; P1 réentraîné et validé sur des charges S5 ; variantes v2 |

## Lancer

```bash
mvn -q compile exec:java                
mvn -q compile exec:java -Dexec.args=out
```

Pour changer un paramètre sans recompiler : copier `tcdrm-cloudsim/src/main/resources/tcdrm.properties`,
le modifier, puis `mvn -q exec:java -Dtcdrm.config=mon.properties`.

Si `org.cloudbus.cloudsim:cloudsim:7.0.1` n'est pas résolu par votre dépôt Maven,
installez-le depuis les sources (tag `7.0.1` de https://github.com/Cloudslab/cloudsim) :
`mvn -pl modules/cloudsim -am install -DskipTests`.

## Organisation

| Fichier | Rôle |
| --- | --- |
| `tcdrm.properties` | Table 1, prix publics (`real.*`, sources en commentaire), seuils, calibrage, paramètres S4 et Sujet 2 |
| `cloud/MultiCloud` | Sites, prix (Table 1), coût et latence des transferts |
| `cloud/CloudFactory` | 9 datacenters CloudSim (fournisseur × région), hôtes, VM |
| `core/TcdrmBroker` | Broker CloudSim : arrivées de requêtes, cloudlets, fins de période, réplicas prêts, prix |
| `core/ReplicaManager` | Algorithme 1 (création), algorithme 3 (suppression), routage, comptabilité |
| `placement/TcdrmPlacement` | Algorithme 2 de l'article |
| `placement/PredictivePlacement` | P2 (Pareto + utilité pondérée) alimenté par P1 et P3 |
| `placement/Consistency` | P3 (mode de cohérence, coût de synchronisation) |
| `forecast/PopularityForecaster`, `forecast/Lstm` | P1 (EMA / régression / LSTM) |
| `exp/Experiments`, `Main` | Scénarios, graines, sensibilités, export CSV |
| `charts/Charts` | Figures (XChart pour les courbes, JFreeChart pour les barres) |

## Ce que CloudSim simule

- 9 datacenters (Google, AWS, Azure × US, UE, AS), 10 hôtes de 8 PE chacun.
- 20 VM par région, créées dans le datacenter dont le CPU est le moins cher (Azure).
- Chaque requête est un événement `QUERY_ARRIVAL`, puis un cloudlet soumis à la VM
  après le temps de rapatriement des données distantes. Le temps de réponse est
  mesuré sur l'horloge CloudSim, de l'arrivée à la fin du cloudlet.
- Les fins de période (algorithme 3, stockage, budget du cycle), les variations de
  prix et la disponibilité des réplicas sont des événements CloudSim.
- La latence et le coût des transferts suivent un modèle hiérarchique
  (local / inter-région / inter-fournisseur) calibré sur l'article. La facturation
  suit la Table 1, pas les coûts déclarés aux datacenters CloudSim.

## Validité et garanties

### Validation de la prédiction (P1) — `exp/ForecastValidation`

| Étape | Choix |
| --- | --- |
| Données | Séries de lectures par (relation, région) et par période de 50 requêtes, charge S4 |
| Entraînement | 10 charges S4 (graines 1000 à 1009), fenêtres de 6 périodes ; cible = taux moyen des 3 périodes suivantes |
| Validation | 1 charge distincte (graine 1100) : arrêt anticipé (patience 5, 40 époques max) et réglage de α de l'EMA |
| Test | 5 charges jamais vues (graines 0 à 4), une prévision toutes les 2 périodes |
| Modèle | LSTM 1 couche, 16 unités, Adam (0,003), lots de 128 ; 5 initialisations, on retient la meilleure en validation |
| Références | Dernière valeur, EMA α = 0,3, EMA α réglé, régression 12 périodes, même phase du cycle précédent, prévision nulle |
| Mesures | MAE et RMSE (accès par période), MAE par tiers de volume et par charge, bootstrap apparié par série (2000 tirages) |

Fichiers : `p1_validation_metrics.csv`, `p1_validation_bootstrap.csv`, `p1_lstm_inits.csv`,
`p1_lstm_training.csv`, `p1_validation_info.csv`, figure `p1_validation.png`.

### Coût de synchronisation (P3) — `placement/Consistency`

    syncCost(rk, s) [$ sur l'horizon τ] = wRate × τ × δ × prix(primaire → s) × f(classe)

- `wRate` : écritures par période sur rk (moyenne du dernier cycle) ; `δ` = taille × `s4.deltaFrac` (GB) ;
  `prix` en $/GB (Table 1, entrée intra-DC comprise) ; `f` = 1 / 0,4 / 0,2 (Critical / ReadMostly / Eventual).
- Unité : **dollars sur l'horizon τ**. La latence inter-cloud n'entre pas dans ce terme monétaire
  ((écritures/h)·ms·GB n'est pas un coût) ; elle est prise en compte en ms : pénalité sur le critère
  temps de réponse (Critical) et contrainte dure sur la latence d'écriture.
- Rapport à CSLA : la part de synchronisation par lecture, `syncCost / lectures prévues sur τ`, est
  ajoutée au coût d'accès comparé à la part de CSLA (voir ci-dessous). À l'exécution, le coût réel de
  synchronisation d'une écriture est imputé à cette écriture et au budget du cycle.

### Contraintes dures de P2 — `placement/PredictivePlacement` (mode `constrained`)

Un site candidat n'est admissible que si, sur les estimations :

1. **TSLA par accès** : latence d'accès à rk depuis le site d'exécution ≤ (TSLA − temps CPU) / nb de relations ;
2. **CSLA par accès** : coût de transfert + part de synchronisation ≤ (CSLA − coût CPU) / nb de relations ;
3. **écritures Critical** : latence primaire → site ≤ TSLA des écritures − temps CPU ;
4. **budget projeté** : le réplica réduit la dépense projetée du cycle, ou dépense projetée + effet ≤ budget ;
5. **amortissement** : coût sur τ avec le réplica (création + stockage + accès + sync, utilisable après sa copie) ≤ coût sans.

Le temps de réponse et le coût d'une requête étant la somme de leurs accès (plus le CPU), respecter
chaque part garantit — sur les estimations — que l'accès à rk ne fait pas sortir la requête de son SLA.
« Aucun réplica » reste toujours admissible. Les compteurs `p2_*` des résumés donnent le nombre de
candidats écartés par contrainte ; `audit_*` mesure, à l'exécution, la part des lectures servies par ces
réplicas qui dépassent leur part de SLA.

**Ex æquo** : U minimal ; à |ΔU| ≤ 1e-12, coût (coût + sync) le plus faible, puis temps de réponse,
puis synchronisation ; à égalité complète, « aucun réplica » puis l'ordre fixe des sites (Table 1).

La variante `Predictive sans contraintes` reproduit l'ancien P2 (filtre budget restant + amortissement)
pour mesurer le coût des garanties.


## Améliorations v2 de TCDRM-Predictive

Quatre variantes optionnelles de P2 (`placement/PredictivePlacement.Options`), toutes désactivées dans
`TCDRM-Predictive` : les résultats S1 à S4 sont inchangés.

| Variante | Mécanisme | Paramètres |
| --- | --- | --- |
| `P-U seul` | argmin U sur tous les candidats, sans filtre de Pareto | — |
| `P-poids dynamiques` | ω_coût × (1 + β·u), u = dépense projetée du cycle / budget | `v2.dynBeta` |
| `P-saturation` | 5e critère d'occupation + contrainte C6 (capacité par site) | `v2.satCapacity`, `v2.satWeight` |
| `P-anti-thrashing` | l'algorithme 3 garde un réplica si P1 ou la saisonnalité prévoient son retour | `v2.retainFactor` |
| `TCDRM-Predictive v2` | poids dynamiques + anti-thrashing | — |

Scalabilité (`Main.scalability`) : temps de chaque décision de placement (`decision_us_mean`), en faisant
varier le nombre de sites (fournisseurs synthétiques) et de requêtes.

Sorties : `results/v2_summary_all_seeds.csv`, `results/v2_scalability.csv` ; figures `v2_scalability.png`,
`v2_dynamic_weights.png`, `v2_saturation.png`, `v2_antithrashing.png`, `v2_summary.png`.

## S2 : figures complémentaires

- `figures/s2_multiseed.png` : moyenne ± écart-type sur 10 graines (temps de réponse, coût lié au placement,
  réplicas créés, lectures au-dessus de TSLA), requêtes simples et complexes.
- `figures/s2_sensitivity.png` et `results/s2_sensitivity_psla.csv` : sensibilité au seuil PSLA (50, 100, 200, 400).
- `figures/s2_replica_sites.png` et `results/s2_replica_sites.csv` : réplicas créés par site et par stratégie.
- En S2, aucun réplica n'est supprimé (une seule période : l'algorithme 3 n'agit pas) et la synchronisation
  coûte 0 $ (aucune écriture).

## S3 et S4 : prix publics

- Prix : `SimConfig.withPrefix("real.")` remplace les prix de la Table 1 par les prix publics (VM 2 vCPU,
  sortie Internet et inter-région, stockage standard, requêtes d'écriture). S1 et S2 gardent la Table 1.
  Avec ces prix, la VM la moins chère est Azure aux US et en Asie, Google en Europe.
- CSLA aux prix publics = CSLA de la Table 1 × 7 (même sévérité relative : rapport des coûts de NoRepLc
  dans le scénario de l'article) ; les TSLA ne changent pas.
- S3 rejoue le scénario de l'article (`Experiments.runArticle`) avec ces prix. Sorties : `s3_summary_all_seeds.csv`,
  `s3_prices.csv`, `s3_replica_sites.csv`, `s3_price_comparison.csv` ; figures `s3_fig*.png`, `s3_multiseed.png`,
  `s3_replica_sites.png`, `s3_price_comparison.png`.
- S4 (`Workloads.dynamic`, clés `s4.*`) : pour chaque graine, `s4.templates` modèles de `s4.relsPerQuery` relations
  sur des sites distincts, sur le principe Q1 = {F1, F5, F20}, Q2 = {F1, F9, F44}, Q3 = {F1, F9, F25} :
  une relation **partagée** (tous les modèles), une **commune** (au moins 2 modèles fréquents, `s4.commonProb`),
  les autres propres à un modèle (**fréquentes**, ou **rares** pour le dernier modèle). Parts de Zipf
  (`s4.zipf`) dans un ordre aléatoire ; modèle rare à `s4.rare.share`, sauf une rafale de
  `s4.rare.burstPeriods` périodes (`s4.rare.burstShare`) placée au hasard. Les clés `s4.p1.window`,
  `s4.p1.minLen` et `s4.p2.horizon` remplacent celles de P1 / P2 (20 périodes seulement).
- Sorties S4 : `s4_templates.csv` (modèles tirés par graine), `s4_summary_all_seeds.csv`, `s4_relations.csv`
  (réplicas créés, supprimés, actifs par rôle de relation), `s4_replica_sites.csv` ; figures `s4_*.png`.
- S5 : les clés `s5.*` remplacent les clés `s4.*` (`s5.queries`, `s5.cycle`, `s5.shiftPeriods`,
  `s5.priceChangePeriod`). Sorties `s5_*.csv`, `p1_s5_*.csv`, `s5_v2_summary_all_seeds.csv` ; figures `s5_*.png`
  (dont `s5_workload.png`, part de chaque modèle par période) et `p1_validation_s5.png`.

## Validation complémentaire

- **Graines** : `dyn.seeds = 30` pour S4 et S5 (S1 à S3 : `seeds = 10`).
- **Modèle intermittent dans S5** (`s5.intermittent.*`) : un modèle fréquent n'est demandé que 4 périodes sur 10,
  ce qui provoque des suppressions suivies de recréations (thrashing).
- **Sensibilité aux choix de modélisation** (`Main.sensitivityRuns`) : CSLA × 5 / 7 / 10 et latences × 0,5 / 1 / 2,
  sur S3 et S5 ; `sensitivity_models.csv`, figures `sensitivity_csla.png` et `sensitivity_latency.png`.
