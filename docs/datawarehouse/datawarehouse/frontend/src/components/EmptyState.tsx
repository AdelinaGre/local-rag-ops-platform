interface EmptyStateProps {
  title: string;
  description?: string;
}

export default function EmptyState({ title, description }: EmptyStateProps) {
  return (
    <div className="flex min-h-[180px] items-center justify-center rounded-lg border border-dashed border-border bg-muted/20 p-6 text-center">
      <div>
        <p className="text-sm font-semibold text-card-foreground">{title}</p>
        {description && (
          <p className="mt-1 max-w-md text-xs leading-5 text-muted-foreground">{description}</p>
        )}
      </div>
    </div>
  );
}
