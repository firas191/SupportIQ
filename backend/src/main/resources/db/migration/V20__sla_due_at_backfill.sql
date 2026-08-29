-- Echeance SLA provisoire pour les tickets ouverts qui n'en ont pas (S8-J1).
--
-- LE TROU QUE CECI FERME
--
-- Au S7-J3, le calcul d'echeance a ete accroche a `TicketAnalyzedListener`, parce que la priorite
-- -- qui determine le budget -- n'est connue qu'apres analyse. La consequence n'avait pas ete
-- anticipee : **un ticket jamais analyse n'a jamais d'echeance**.
--
-- L'exclusion qui en decoule est doublement silencieuse :
--   * le lot de scoring (`ai-service/app/sla/service.py`) trie par `sla_due_at DESC NULLS LAST` avec
--     un plafond de 5 000 : les tickets sans echeance sont rejetes en queue de tri et ne sont jamais
--     atteints ;
--   * la liste applique le meme `NULLS LAST` -- decision du S7-J3, « le tri le plus dangereux est
--     celui qui met en tete ce dont on ne sait rien » -- qui les repousse egalement.
--
-- Un ticket sortait donc du dispositif SLA sans qu'aucune requete n'echoue, aucun compteur ne bouge,
-- aucun journal ne s'ecrive. Constate au S7-J5 : `sla_due_at` etait NULL sur l'integralite du corpus
-- importe, et la requete de verite terrain renvoyait zero depassement et zero respect.
--
-- Desormais l'echeance est posee **a la creation** (`Ticket.onCreate`, rappel de cycle de vie JPA
-- que les quatre chemins de creation traversent necessairement), puis affinee a l'analyse.
-- Cette migration ne fait que rattraper l'existant.

-- ---------------------------------------------------------------------------------------------
-- SEULEMENT LES TICKETS OUVERTS, et c'est le seul arbitrage de cette migration
-- ---------------------------------------------------------------------------------------------
--
-- Pour un ticket **ouvert**, l'echeance est operationnelle : elle dit sous combien de temps il faut
-- repondre, elle pilote la file, et l'appliquer par defaut est une decision de fonctionnement --
-- l'engagement sous lequel on travaille tant qu'on n'en sait pas plus.
--
-- Pour un ticket **deja resolu**, ce serait une mesure : on comparerait `resolved_at` a une
-- echeance qui n'a jamais existe pendant sa vie, et on en tirerait un taux de depassement. Ce taux
-- ne mesurerait pas la performance de l'equipe, il mesurerait notre hypothese par defaut.
--
-- **Une echeance qui existait pendant que le ticket etait ouvert est un engagement ; une echeance
-- inventee apres coup est une fiction.** Les tickets resolus sans echeance restent donc NULL, et la
-- verite terrain du depassement reste incalculable pour eux -- ce qui est la reponse honnete.
UPDATE tickets
SET sla_due_at = created_at + interval '24 hours'
WHERE sla_due_at IS NULL
  AND resolved_at IS NULL;
