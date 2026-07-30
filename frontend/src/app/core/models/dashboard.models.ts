/** Cartes KPI (miroir de KpiResponse cote backend). Les taux sont deja calcules serveur. */
export interface Kpi {
  totalTickets: number;
  newTickets: number;
  resolvedTickets: number;
  analyzedTickets: number;
  highPriority: number;
  negativeSentiment: number;
  escalatedToLlm: number;
  highPriorityRate: number;
  negativeRate: number;
  escalationRate: number;
  avgConfidence: number;
}

export interface CategoryTrendPoint {
  day: string;        // ISO (yyyy-MM-dd)
  category: string;
  count: number;
}

export interface CountByLabel {
  label: string;
  count: number;
}

export interface HourlyPoint {
  hour: number;       // 0-23
  count: number;
}

/** Toutes les series des graphiques, renvoyees en un seul appel (miroir de TrendsResponse). */
export interface Trends {
  daily: CategoryTrendPoint[];
  byCategory: CountByLabel[];
  bySentiment: CountByLabel[];
  byPriority: CountByLabel[];
  hourly: HourlyPoint[];
}
