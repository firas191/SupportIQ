import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Digest, DigestStatus } from '../models/digest.models';

/** Synthese hebdomadaire (S6-J4). Reserve aux responsables cote serveur. */
@Injectable({ providedIn: 'root' })
export class DigestService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/digests`;

  list(): Observable<Digest[]> {
    return this.http.get<Digest[]>(this.base);
  }

  status(): Observable<DigestStatus> {
    return this.http.get<DigestStatus>(`${this.base}/status`);
  }

  /** Produit la synthese de la semaine ecoulee, ou renvoie celle qui existe deja. */
  generate(): Observable<Digest> {
    return this.http.post<Digest>(this.base, {});
  }

  /**
   * Reproduit la synthese d'une semaine deja generee.
   *
   * `force=true` n'est **jamais** envoye par le declenchement automatique : ecraser un digest
   * deja parti par courriel rendrait l'archive incoherente avec ce que les destinataires ont lu.
   * C'est une action explicite d'un responsable, sur une semaine qu'il designe.
   */
  regenerate(weekStart: string): Observable<Digest> {
    return this.http.post<Digest>(`${this.base}?week=${weekStart}&force=true`, {});
  }

  send(id: number): Observable<Digest> {
    return this.http.post<Digest>(`${this.base}/${id}/send`, {});
  }

  /**
   * PDF de la synthese, regenere a la demande cote serveur.
   *
   * Recupere en **blob** et non par un simple lien : l'API exige un jeton, et un
   * `<a href>` ne passe pas par l'intercepteur qui l'ajoute. Le navigateur
   * recevrait un 401 affiche comme une page d'erreur.
   */
  pdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/${id}/pdf`, { responseType: 'blob' });
  }
}
