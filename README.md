# TCDRM v2 — simulation CloudSim 7.0.1

Reproduction de la stratégie TCDRM (Bernardin et al., JLISS 12(3), 2025) et du
Sujet 2 (TCDRM-Predictive : P1 → P3 → P2) sur CloudSim 7.0.1.

| Scénario | Contenu |
| --- | --- |
| S1 | Scénario de l'article : TCDRM (algorithmes 1-2-3) contre NoRepLc |
| S2 | Même scénario, algorithme 2 remplacé par P1 → P3 → P2 |
| S3 | Scénario dynamique en nombre de requêtes : 3 000 requêtes de la forme de l'article, périodes de 50 requêtes, cycles follow-the-sun de 750 requêtes, popularité non stationnaire, écritures, variation des prix (période 30), budget par cycle ; seuils de la Table 1 ; ablation P2 / P1+P2 / P3+P2 + sensibilité au taux d'écriture |

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
| `tcdrm.properties` | Table 1, seuils, calibrage, paramètres S3 et Sujet 2 |
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
| Données | Séries de lectures par (relation, région) et par période de 50 requêtes, charge S3 |
| Entraînement | 2 charges S3 (graines 1000 et 1001), fenêtres de 15 périodes ; cible = taux moyen des 5 périodes suivantes |
| Validation | 1 charge distincte (graine 1100) : arrêt anticipé (patience 5, 40 époques max) et réglage de α de l'EMA |
| Test | 3 charges jamais vues (graines 0, 1, 2), une prévision toutes les 2 périodes |
| Modèle | LSTM 1 couche, 16 unités, Adam (0,003), lots de 128 ; 5 initialisations, on retient la meilleure en validation |
| Références | Dernière valeur, EMA α = 0,3, EMA α réglé, régression 12 périodes, même phase du cycle précédent, prévision nulle |
| Mesures | MAE et RMSE (accès par période), MAE par tiers de volume et par charge, bootstrap apparié par série (2000 tirages) |

Fichiers : `p1_validation_metrics.csv`, `p1_validation_bootstrap.csv`, `p1_lstm_inits.csv`,
`p1_lstm_training.csv`, `p1_validation_info.csv`, figure `p1_validation.png`.

### Coût de synchronisation (P3) — `placement/Consistency`

    syncCost(rk, s) [$ sur l'horizon τ] = wRate × τ × δ × prix(primaire → s) × f(classe)

- `wRate` : écritures par période sur rk (moyenne du dernier cycle) ; `δ` = taille × `s3.deltaFrac` (GB) ;
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

